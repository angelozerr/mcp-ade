/*******************************************************************************
 * Copyright (c) 2026 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Angelo ZERR - initial API and implementation
 *******************************************************************************/
package com.ibm.mcp.languagetools.workspace;

import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.dap.server.DapServerConfig;
import com.ibm.mcp.languagetools.configuration.Configuration;
import com.ibm.mcp.languagetools.configuration.WorkspaceConfiguration;
import com.ibm.mcp.languagetools.installer.InstallationException;
import com.ibm.mcp.languagetools.lsp.LspInstanceRegistry;
import com.ibm.mcp.languagetools.lsp.client.LspClientFeatures;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import com.ibm.mcp.languagetools.lsp.server.LspServerFactoryRegistry;
import com.ibm.mcp.languagetools.lsp.server.LspServerStatusChangeEvent;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.progress.ProgressBroadcaster;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressStep;
import com.ibm.mcp.languagetools.server.ActivationCondition;
import com.ibm.mcp.languagetools.server.ServerBase;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.watcher.WorkspaceFileWatcher;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Represents a workspace (project) with its language server and debug adapter instances.
 * A workspace can have multiple LSP servers (e.g., JDT.LS + Qute LS) and DAP servers (e.g., vscode-js-debug).
 */
public class Workspace {

    private static final Logger LOG = Logger.getLogger(Workspace.class);

    private final Application application;

    // Workspace
    private final URI rootUri;
    private final Path rootPath;
    private final String normalizedRootUriString; // Cached normalized URI string (no trailing slash)
    private final IdeConfiguration ideConfiguration;
    private final WorkspaceConfiguration configuration;

    // LSP
    private final TraceCollector lspTraceCollector;

    private final Map<String, LspServer> lspServers = new ConcurrentHashMap<>();
    private final Map<String, McpClientInfo> mcpClientConnections = new ConcurrentHashMap<>();
    private Consumer<LspServerStatusChangeEvent> statusChangeCallback;

    // Activation condition cache: serverId -> whether the server should be activated for this workspace
    private final Map<String, Boolean> activationCache = new ConcurrentHashMap<>();

    // File watcher
    private WorkspaceFileWatcher fileWatcher;
    private final Map<String, List<FileEvent>> pendingServerEvents = new ConcurrentHashMap<>();

    // Build state
    private volatile boolean needsFullBuild = true;

    public record McpClientInfo(
            String connectionId,
            String name,
            Instant connectedAt
    ) {

    }

    public Workspace(URI rootUri,
                     Application application) {
        this.rootUri = rootUri;
        this.rootPath = Paths.get(rootUri);
        // Cache normalized URI string (remove trailing slash for consistency across the app)
        this.normalizedRootUriString = rootUri.toString();
        this.application = application;
        this.lspTraceCollector = application.getLspTraceCollector();
        this.ideConfiguration = new IdeConfiguration(rootPath,
                application.getIdeConfigurationProviders(),
                application.getIdeConfigurationStrategy());
        this.ideConfiguration.watch();
        this.configuration = new WorkspaceConfiguration(rootPath, application.getConfiguration());
        this.configuration.watch();
    }

    /**
     * Set callback for LSP server status changes.
     */
    public void setServerStatusChangeCallback(Consumer<LspServerStatusChangeEvent> callback) {
        this.statusChangeCallback = callback;
    }

    /**
     * Register status change callback for a server.
     * Factorized method to avoid code duplication.
     * Works for both LSP and DAP servers since they both extend ServerBase.
     */
    private void registerServerStatusCallback(ServerBase<?> server) {
        server.addStatusChangeListener((oldStatus, newStatus) -> {
            if (statusChangeCallback != null) {
                statusChangeCallback.accept(new LspServerStatusChangeEvent(
                        rootUri,
                        server.getId(),
                        oldStatus,
                        newStatus
                ));
            }
            if (newStatus == ServerStatus.RUNNING && server.isReady()) {
                replayPendingFileEvents(server.getId());
            }
        });
    }

    /**
     * Add an LSP server to this workspace (serverHome calculated from PathManager).
     *
     * @param config Server configuration
     * @return
     */
    public LspServer addLspServer(LspServerConfig config) {
        // TraceCollector is now configured in createLspServer()
        var lspServer = createLspServer(config);
        LOG.infof("Added LSP server '%s' to workspace: %s", config.getServerId(), rootUri);
        return lspServer;
    }


    /**
     * Restart a specific LSP server (shutdown old, create new, start).
     * Will try to connect to IDE instance if available.
     *
     * @param serverId Server ID
     * @param progressMonitor Progress monitor (never null)
     */
    public CompletableFuture<Void> restartLspServer(String serverId, ProgressMonitor progressMonitor) {
        LspServerConfig serverConfig = application.getLspServerConfig(serverId);
        String serverName = serverConfig != null ? serverConfig.getName() : serverId;

        ProgressMonitor installMonitor = progressMonitor.beginStep(ProgressStep.INSTALLING);
        installMonitor.reportProgress(0.0, "Installing " + serverName);
        return prepareRestartLspServer(serverId, installMonitor)
                .thenCompose(server -> {
                    progressMonitor.beginStep(ProgressStep.STARTING);
                    progressMonitor.beginStep(ProgressStep.INITIALIZING);
                    return server.initialize();
                })
                .thenRun(() -> {
                    progressMonitor.setComplete();
                    LOG.infof("Restarted LSP server '%s' for workspace: %s", serverId, rootUri);
                })
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to restart LSP server '%s'", serverId);
                    throw new RuntimeException("Failed to restart server: " + ex.getMessage(), ex);
                });
    }

    /**
     * Prepare a restart: shutdown old server, create new, install and start (but NOT initialize).
     * Callers can chain server.initialize() after step transitions.
     */
    public CompletableFuture<LspServer> prepareRestartLspServer(String serverId, ProgressMonitor progressMonitor) {
        LspServerConfig serverConfig = application.getLspServerConfig(serverId);
        if (serverConfig == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Server not found: " + serverId));
        }

        LspServer oldServer = getLspServer(serverId);

        CompletableFuture<Void> shutdownFuture;
        if (oldServer != null && oldServer.getStatus() != ServerStatus.STOPPED) {
            shutdownFuture = oldServer.shutdown();
        } else {
            shutdownFuture = CompletableFuture.completedFuture(null);
        }

        return shutdownFuture.thenCompose(v -> {
            LspServer newServer = createLspServer(serverConfig);
            lspServers.put(serverId, newServer);

            return newServer.start(progressMonitor)
                    .thenApply(initV -> newServer);
        });
    }

    /**
     * Start an MCP-managed LSP server only (do not connect to IDE instance).
     * Handles installation if needed before starting.
     *
     * @param serverId Server ID
     * @param progressMonitor Progress monitor (never null)
     */
    public CompletableFuture<Void> startManagedLspServer(String serverId, ProgressMonitor progressMonitor) {
        return startManagedLspServer(serverId, progressMonitor, null);
    }

    public CompletableFuture<Void> startManagedLspServer(String serverId, ProgressMonitor progressMonitor,
                                                          OperationEntry serverEntry) {
        LspServerConfig serverConfig = application.getLspServerConfig(serverId);
        String serverName = serverConfig != null ? serverConfig.getName() : serverId;

        OperationEntry installChild = serverEntry != null ? serverEntry.addChild("installing") : null;

        ProgressMonitor installMonitor = progressMonitor.beginStep(ProgressStep.INSTALLING);
        installMonitor.reportProgress(0.0, "Installing " + serverName);
        return prepareManagedLspServer(serverId, installMonitor)
                .thenCompose(server -> {
                    if (installChild != null) {
                        installChild.complete();
                    }
                    if (serverEntry != null) {
                        serverEntry.addChild("starting");
                        server.setOperationEntry(serverEntry);
                    }
                    progressMonitor.beginStep(ProgressStep.STARTING);
                    progressMonitor.beginStep(ProgressStep.INITIALIZING);
                    return server.initialize();
                })
                .thenRun(() -> {
                    progressMonitor.setComplete();
                    LOG.infof("Started MCP-managed LSP server '%s' for workspace: %s", serverId, rootUri);
                });
    }

    /**
     * Prepare a managed server: shutdown old, create new, install and start (but NOT initialize).
     * Callers can chain server.initialize() after step transitions.
     */
    public CompletableFuture<LspServer> prepareManagedLspServer(String serverId, ProgressMonitor progressMonitor) {
        LspServerConfig serverConfig = application.getLspServerConfig(serverId);
        if (serverConfig == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Server not found: " + serverId));
        }

        progressMonitor.reportProgress("Starting " + serverConfig.getName() + "...");

        LspServer oldServer = getLspServer(serverId);

        CompletableFuture<Void> shutdownFuture;
        if (oldServer != null && oldServer.getStatus() != ServerStatus.STOPPED) {
            shutdownFuture = oldServer.shutdown();
        } else {
            shutdownFuture = CompletableFuture.completedFuture(null);
        }

        return shutdownFuture
                .thenCompose(v -> {
            LspServer newServer = createLspServer(serverConfig);

            if (serverConfig.getInstaller() != null) {
                newServer.setStatus(ServerStatus.INSTALLING);
            }

            return newServer.startManagedOnly(progressMonitor)
                    .thenApply(initV -> newServer)
                    .exceptionally(ex -> {
                        LOG.errorf(ex, "Failed to start MCP-managed LSP server '%s'", serverId);

                        Throwable cause = ex.getCause();
                        if (cause instanceof InstallationException
                                || ex instanceof InstallationException) {
                            newServer.setStatus(ServerStatus.INSTALL_FAILED);
                        } else {
                            String errorMsg = cause != null ? cause.getMessage() : ex.getMessage();
                            newServer.setStatus(ServerStatus.START_FAILED, errorMsg);
                        }

                        throw new RuntimeException("Failed to start managed server: " + ex.getMessage(), ex);
                    });
        });
    }

    /**
     * Ensure an LSP server is started in this workspace.
     * Handles:
     * - Checking for external instances (launched by IDE)
     * - Installing if needed
     * - Starting and initializing the server
     *
     * @param serverId        The server ID to ensure is started
     * @param progressMonitor
     * @return CompletableFuture<LspServer> that completes when server is started (not necessarily ready)
     */
    public CompletableFuture<LspServer> ensureLspServerStarted(String serverId,
                                                               ProgressMonitor progressMonitor) {
        return ensureLspServerStarted(serverId, progressMonitor, null);
    }

    public CompletableFuture<LspServer> ensureLspServerStarted(String serverId,
                                                               ProgressMonitor progressMonitor,
                                                               OperationEntry serverEntry) {
        // Already running?
        if (hasLspServer(serverId)) {
            LspServer server = getLspServer(serverId);
            if (server != null && server.getStatus() != ServerStatus.STOPPED) {
                LOG.debugf("Server '%s' already running in workspace: %s", serverId, rootUri);
                return CompletableFuture.completedFuture(server);
            }
        }

        LOG.infof("Ensuring server '%s' is started in workspace: %s", serverId, rootUri);

        LspServerConfig config = application.getLspServerConfig(serverId);
        if (config == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Server config not found: " + serverId)
            );
        }

        // Check if there's an external instance (launched by IDE) first
        var externalInstance = getExternalInstance(serverId);
        if (externalInstance != null) {
            LOG.infof("Found external %s instance (port %d, PID %d), connecting...",
                config.getName(), externalInstance.port, externalInstance.pid);

            // Add server to workspace if not already present
            if (!hasLspServer(serverId)) {
                addLspServer(config);
            }

            // Start and initialize (will connect to socket)
            var server = getLspServer(serverId);
            if (server != null) {
                return server.start(progressMonitor)
                    .thenCompose(v -> server.initialize())
                    .thenApply(v -> server)
                    .exceptionally(ex -> {
                        LOG.errorf(ex, "Failed to connect to external %s", config.getName());
                        return null;
                    });
            }
        }

        // No external instance - start our own managed server (handles installation automatically)
        return startManagedLspServer(serverId, progressMonitor, serverEntry)
            .thenApply(v -> {
                LspServer server = getLspServer(serverId);
                if (server == null) {
                    throw new IllegalStateException("Server failed to start: " + serverId);
                }
                return server;
            });
    }

    /**
     * Ensure an LSP server is started and ready in this workspace.
     * This method calls ensureLspServerStarted() and waits until the server is ready.
     * Handles:
     * - Checking for external instances (launched by IDE)
     * - Installing if needed
     * - Starting, initializing and waiting for the server to be ready
     *
     * @param serverId The server ID to ensure is ready
     * @return CompletableFuture<LspServer> that completes when server is ready
     */
    public CompletableFuture<LspServer> ensureLspServerReady(String serverId,
                                                             ProgressMonitor progressMonitor) {
        return ensureLspServerReady(serverId, progressMonitor, null);
    }

    public CompletableFuture<LspServer> ensureLspServerReady(String serverId,
                                                             ProgressMonitor progressMonitor,
                                                             OperationEntry serverEntry) {
        return ensureLspServerStarted(serverId, progressMonitor, serverEntry)
                .thenCompose(server -> server.waitForReady().thenApply(v -> server));
    }

    /**
     * Shutdown the workspace (stop all LSP servers).
     */
    public CompletableFuture<Void> shutdown() {
        LOG.infof("Shutting down workspace: %s", rootUri);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (LspServer server : lspServers.values()) {
            futures.add(server.shutdown());
        }

        return CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    stopFileWatcher();
                    lspServers.clear();
                    ideConfiguration.unwatch();
                    configuration.unwatch();
                    LOG.infof("Workspace shut down: %s", rootUri);
                });
    }

    /**
     * Check if workspace has a language server for the given ID.
     */
    public boolean hasLspServer(String serverId) {
        return lspServers.containsKey(serverId);
    }

    /**
     * Check if a server should be activated for this workspace based on its activation condition.
     * Results are cached per server ID.
     */
    public boolean isServerActivated(ServerConfigBase config) {
        return activationCache.computeIfAbsent(config.getServerId(), id -> {
            ActivationCondition condition = config.getActivateWhen();
            if (condition == null) {
                return true;
            }
            return evaluateActivationCondition(condition);
        });
    }

    private boolean evaluateActivationCondition(ActivationCondition condition) {
        if (condition.getFileExists() != null) {
            Path file = rootPath.resolve(condition.getFileExists());
            boolean exists = java.nio.file.Files.exists(file);
            LOG.debugf("Activation condition fileExists '%s': %s", condition.getFileExists(), exists);
            return exists;
        }
        // TODO: globPattern support (shared workspace scan)
        // TODO: command support (delegate to bound server)
        return true;
    }

    public void refreshActivationCache() {
        activationCache.clear();
    }

    /**
     * Get a language server by ID.
     */
    public LspServer getLspServer(String id) {
        return lspServers.get(id);
    }

    /**
     * Add a DAP server configuration to this workspace.
     * DAP servers are not started automatically - they are started on-demand during debug sessions.
     */
    public void addDapServer(DapServerConfig config) {
        if (config.getTraceCollector() == null) {
            config.setTraceCollector(application.getDapTraceCollector());
        }
        LOG.infof("Added DAP server to workspace %s: %s", rootUri, config.getServerId());
    }

    /**
     * Get status for a server.
     */
    public ServerStatus getLspServerStatus(String serverId) {
        LspServer server = getLspServer(serverId);
        return server != null ? server.getStatus() : ServerStatus.STOPPED;
    }

    /**
     * Get external instance info for a server (launched by an IDE).
     */
    public LspInstanceRegistry.InstanceInfo getExternalInstance(String serverId) {
        try {
            String workspacePath = Paths.get(rootUri).toString();
            return LspInstanceRegistry.findInstance(workspacePath, serverId);
        } catch (Exception e) {
            LOG.debugf("Failed to check for external instance of %s: %s", serverId, e.getMessage());
            return null;
        }
    }

    public URI getRootUri() {
        return rootUri;
    }

    public Path getRootPath() {
        return rootPath;
    }

    /**
     * Get normalized root URI string (without trailing slash).
     * Use this method for consistency when sending URIs to the frontend or comparing URIs.
     */
    public String getNormalizedUri() {
        return normalizedRootUriString;
    }


    /**
     * Get IDE configuration (settings from .vscode/settings.json, .bob/settings.json, etc.)
     * Used for LSP workspace/configuration responses.
     */
    public Configuration getIdeConfiguration() {
        return ideConfiguration;
    }

    /**
     * Get workspace-level application configuration (overrides global settings).
     */
    public WorkspaceConfiguration getWorkspaceConfiguration() {
        return configuration;
    }

    /**
     * Add an MCP client to this workspace.
     *
     * @param connectionId MCP connection ID
     * @param clientName   Client name (e.g., "claude-code 2.1.183")
     * @return true if this is a new client, false if it already existed
     */
    public boolean addMcpClient(String connectionId, String clientName) {
        if (connectionId != null && !connectionId.isEmpty()) {
            boolean isNew = !mcpClientConnections.containsKey(connectionId);
            if (isNew) {
                mcpClientConnections.put(connectionId, new McpClientInfo(
                        connectionId,
                        clientName,
                        java.time.Instant.now()
                ));
                LOG.infof("Added MCP client '%s' [%s] to workspace: %s (total: %d)",
                        clientName, connectionId, rootUri, mcpClientConnections.size());
            } else {
                LOG.debugf("MCP client '%s' [%s] already connected to workspace: %s",
                        clientName, connectionId, rootUri);
            }
            return isNew;
        }
        return false;
    }

    /**
     * Get all MCP client connections.
     */
    public Map<String, McpClientInfo> getMcpClientConnections() {
        return Collections.unmodifiableMap(mcpClientConnections);
    }

    public Application getApplication() {
        return application;
    }

    // LSP servers

    public Collection<LspServer> getLspServers() {
        return lspServers.values();
    }

    private LspServer createLspServer(LspServerConfig serverConfig) {
        // Set trace collector for installation and LSP communication support
        if (serverConfig.getTraceCollector() == null) {
            serverConfig.setTraceCollector(lspTraceCollector);
        }

        // Create new server instance using factory
        LspServer newServer = LspServerFactoryRegistry.getInstance().createServer(serverConfig, this);
        lspServers.put(newServer.getId(), newServer);
        // Register status change callback
        registerServerStatusCallback(newServer);
        return newServer;
    }

    // ===== File Watcher =====

    /**
     * Start the file watcher for this workspace if enabled in settings.
     */
    public void startFileWatcherIfEnabled() {
        if (fileWatcher != null && fileWatcher.isRunning()) {
            return;
        }
        boolean enabled = configuration.resolveBoolean("fileWatchers.enabled", true).value();
        if (!enabled) {
            LOG.debugf("File watchers disabled for workspace: %s", rootUri);
            return;
        }
        if (!"file".equals(rootUri.getScheme())) {
            LOG.infof("File watchers not supported for remote workspace: %s", rootUri);
            return;
        }
        Set<String> additionalExcludes = null;
        String excludePatterns = configuration.resolveString("fileWatchers.excludePatterns", null).value();
        if (excludePatterns != null && !excludePatterns.isBlank()) {
            additionalExcludes = new HashSet<>(Arrays.asList(excludePatterns.split(",")));
            additionalExcludes.removeIf(String::isBlank);
        }
        fileWatcher = new WorkspaceFileWatcher(rootPath, this::onFileChanges, additionalExcludes);
        fileWatcher.start();
    }

    /**
     * Stop the file watcher.
     */
    public void stopFileWatcher() {
        if (fileWatcher != null) {
            fileWatcher.stop();
            fileWatcher = null;
            needsFullBuild = true;
        }
    }

    /**
     * Check if the file watcher is running.
     */
    public boolean isFileWatcherRunning() {
        return fileWatcher != null && fileWatcher.isRunning();
    }

    public boolean isNeedsFullBuild() {
        return needsFullBuild;
    }

    public void setNeedsFullBuild(boolean needsFullBuild) {
        this.needsFullBuild = needsFullBuild;
    }

    public CompletableFuture<String> refreshWorkspace() {
        return refreshWorkspace("refresh-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public CompletableFuture<String> refreshWorkspace(String taskId) {
        String workspaceName = rootPath.getFileName().toString();
        String title = "Refresh " + workspaceName;
        ProgressBroadcaster broadcaster = application.getProgressBroadcaster();

        if (broadcaster != null) {
            broadcaster.taskRunning(taskId, normalizedRootUriString, title, 0.0, "Refreshing...");
        }

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (LspServer server : lspServers.values()) {
            if (isServerStarted(server)) {
                futures.add(server.refreshWorkspace());
            }
        }
        if (futures.isEmpty()) {
            if (broadcaster != null) {
                broadcaster.taskCompleted(taskId, normalizedRootUriString, title);
            }
            return CompletableFuture.completedFuture("No ready servers");
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    String result = futures.stream()
                            .map(f -> f.getNow(""))
                            .filter(s -> !s.isEmpty())
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse("OK");
                    if (broadcaster != null) {
                        broadcaster.taskCompleted(taskId, normalizedRootUriString, title);
                    }
                    return result;
                })
                .exceptionally(error -> {
                    if (broadcaster != null) {
                        broadcaster.taskFailed(taskId, normalizedRootUriString, title, error.getMessage());
                    }
                    return "Refresh failed: " + error.getMessage();
                });
    }

    public CompletableFuture<String> buildWorkspace() {
        return buildWorkspace("build-" + UUID.randomUUID().toString().substring(0, 8));
    }

    public CompletableFuture<String> buildWorkspace(String taskId) {
        boolean fullBuild = needsFullBuild;
        String workspaceName = rootPath.getFileName().toString();
        String title = (fullBuild ? "Full Build " : "Build ") + workspaceName;
        ProgressBroadcaster broadcaster = application.getProgressBroadcaster();

        if (broadcaster != null) {
            broadcaster.taskRunning(taskId, normalizedRootUriString, title, 0.0, "Building...");
        }

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (LspServer server : lspServers.values()) {
            if (isServerStarted(server)) {
                futures.add(server.buildWorkspace(fullBuild));
            }
        }
        if (futures.isEmpty()) {
            if (broadcaster != null) {
                broadcaster.taskCompleted(taskId, normalizedRootUriString, title);
            }
            return CompletableFuture.completedFuture("No ready servers");
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    String result = futures.stream()
                            .map(f -> f.getNow(""))
                            .filter(s -> !s.isEmpty())
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse("OK");
                    if (broadcaster != null) {
                        broadcaster.taskCompleted(taskId, normalizedRootUriString, title);
                    }
                    return result;
                })
                .exceptionally(error -> {
                    if (broadcaster != null) {
                        broadcaster.taskFailed(taskId, normalizedRootUriString, title, error.getMessage());
                    }
                    return "Build failed: " + error.getMessage();
                });
    }

    private static boolean isServerStarted(LspServer server) {
        ServerStatus status = server.getStatus();
        return status == ServerStatus.RUNNING || status == ServerStatus.INDEXING;
    }

    private void onFileChanges(List<FileEvent> events) {
        LOG.infof("File watcher detected %d events, dispatching to %d servers", events.size(), lspServers.size());
        for (LspServer server : lspServers.values()) {
            List<FileEvent> matchingEvents = filterByPatterns(events, server);
            if (matchingEvents.isEmpty()) {
                LOG.debugf("No matching events for server %s after pattern filtering", server.getId());
                continue;
            }

            if (server.getStatus() == ServerStatus.RUNNING && server.isReady()) {
                LOG.infof("Sending %d didChangeWatchedFiles to server %s", matchingEvents.size(), server.getId());
                server.sendDidChangeWatchedFiles(matchingEvents);
                List<FileEvent> pending = pendingServerEvents.remove(server.getId());
                if (pending != null && !pending.isEmpty()) {
                    LOG.infof("Replaying %d pending events for server %s", pending.size(), server.getId());
                    server.sendDidChangeWatchedFiles(pending);
                }
            } else {
                LOG.infof("Server %s not ready (status=%s, ready=%s), queuing %d events",
                        server.getId(), server.getStatus(), server.isReady(), matchingEvents.size());
                pendingServerEvents.computeIfAbsent(server.getId(), k -> new CopyOnWriteArrayList<>())
                        .addAll(matchingEvents);
            }
        }
    }

    /**
     * Replay pending file events for a server that just became ready.
     * Called from server status change listener.
     */
    public void replayPendingFileEvents(String serverId) {
        List<FileEvent> pending = pendingServerEvents.remove(serverId);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        LspServer server = getLspServer(serverId);
        if (server != null && server.getStatus() == ServerStatus.RUNNING && server.isReady()) {
            LOG.infof("Replaying %d pending file events for server: %s", pending.size(), serverId);
            server.sendDidChangeWatchedFiles(pending);
        }
    }

    private List<FileEvent> filterByPatterns(List<FileEvent> events, LspServer server) {
        List<PathMatcher> matchers = new ArrayList<>();

        // Dynamic patterns from registerCapability
        LspClientFeatures features = server.getClientFeatures();
        for (FileSystemWatcher w : features.getFileWatchers()) {
            String pattern = w.getGlobPattern().getLeft();
            if (pattern == null) {
                continue;
            }
            try {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + pattern));
            } catch (Exception e) {
                // invalid pattern
            }
        }

        // Static patterns from server.json fileWatchers
        LspServerConfig config = server.getConfig();
        if (config.getFileWatchers() != null) {
            for (LspServerConfig.FileWatcherPattern p : config.getFileWatchers()) {
                try {
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + p.getGlobPattern()));
                } catch (Exception e) {
                    // invalid pattern
                }
            }
        }

        if (matchers.isEmpty()) {
            return events;
        }

        return events.stream()
                .filter(event -> {
                    try {
                        Path filePath = rootPath.relativize(Path.of(URI.create(event.getUri())));
                        return matchers.stream().anyMatch(m -> m.matches(filePath));
                    } catch (Exception e) {
                        return true;
                    }
                })
                .toList();
    }

    /**
     * Notify this workspace of external file changes (from agent or admin).
     * Sends didChangeWatchedFiles to all matching running servers.
     */
    public void notifyFileChanges(List<FileEvent> events) {
        onFileChanges(events);
    }

    // DAP servers

}

