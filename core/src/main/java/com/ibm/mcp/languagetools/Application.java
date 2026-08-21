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
package com.ibm.mcp.languagetools;

import com.ibm.mcp.languagetools.bsp.server.BspServer;
import com.ibm.mcp.languagetools.bsp.server.BspServerConfig;
import com.ibm.mcp.languagetools.dap.server.DapServerConfig;
import com.ibm.mcp.languagetools.tools.ToolException;
import com.ibm.mcp.languagetools.utils.UriUtils;
import com.ibm.mcp.languagetools.extension.ExtensionRegistry;
import com.ibm.mcp.languagetools.installer.InstallResult;
import com.ibm.mcp.languagetools.installer.InstallerListener;
import com.ibm.mcp.languagetools.installer.TraceProgressMonitor;
import com.ibm.mcp.languagetools.language.LanguageRegistry;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import com.ibm.mcp.languagetools.server.ServerStatusChangeEvent;
import com.ibm.mcp.languagetools.event.ServerEnabledChangeEvent;
import com.ibm.mcp.languagetools.operation.OperationContext;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.mcp.McpClientChangeEvent;
import com.ibm.mcp.languagetools.mcp.McpClientTracker;
import com.ibm.mcp.languagetools.mcp.trace.McpTraceCollector;
import com.ibm.mcp.languagetools.progress.ProgressBroadcaster;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressStep;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.configuration.ApplicationConfiguration;
import com.ibm.mcp.languagetools.configuration.Configuration;
import com.ibm.mcp.languagetools.configuration.ServerTrace;
import com.ibm.mcp.languagetools.trace.NoOpTraceCollectorFactory;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.trace.TraceCollectorFactory;
import com.ibm.mcp.languagetools.trace.TraceKind;
import com.ibm.mcp.languagetools.workspace.Workspace;
import com.ibm.mcp.languagetools.workspace.WorkspaceChangeEvent;
import com.ibm.mcp.languagetools.workspace.IdeConfigurationProvider;
import com.ibm.mcp.languagetools.workspace.IdeConfigurationProviderRegistry;
import com.ibm.mcp.languagetools.workspace.IdeConfigurationStrategy;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages multiple workspaces, each with its own language server instances.
 * Workspaces are created dynamically on-demand.
 */
@ApplicationScoped
public class Application {

    private static final Logger LOG = Logger.getLogger(Application.class);

    // Global
    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    PathManager pathManager;

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @Inject
    ExtensionRegistry extensionRegistry;

    // Workspace
    @Inject
    Event<WorkspaceChangeEvent> workspaceChangeEvent;

    // ----------- LSP servers

    @Inject
    Event<ServerStatusChangeEvent> serverStatusChangeEvent;

    // ----------- DAP servers

    @Inject
    Event<ServerEnabledChangeEvent> serverEnabledChangeEvent;

    // ----------- MCP servers

    @Inject
    McpClientTracker mcpClientTracker;

    @Inject
    Event<McpClientChangeEvent> mcpClientChangeEvent;

    @Inject
    io.quarkiverse.mcp.server.runtime.ConnectionManager connectionManager;

    @Inject
    jakarta.enterprise.inject.Instance<ProgressBroadcaster> progressBroadcasterInstance;

    private final Map<URI, Workspace> workspaces = new ConcurrentHashMap<>();

    private final ContributionManager contributionManager;

    // Trace collectors loaded via SPI
    private final TraceCollector lspTraceCollector;
    private final TraceCollector dapTraceCollector;
    private final TraceCollector bspTraceCollector;
    private final McpTraceCollector mcpTraceCollector;

    public Application() {
        this.contributionManager = new ContributionManager(this);
        TraceCollectorFactory factory = ServiceLoader.load(TraceCollectorFactory.class)
                .findFirst()
                .orElse(new NoOpTraceCollectorFactory());
        this.lspTraceCollector = factory.createTraceCollector(TraceKind.LSP);
        this.dapTraceCollector = factory.createTraceCollector(TraceKind.DAP);
        this.bspTraceCollector = factory.createTraceCollector(TraceKind.BSP);
        this.mcpTraceCollector = factory.createMcpTraceCollector();
        LOG.infof("TraceCollectorFactory: %s (enabled=%s)", factory.getClass().getSimpleName(), lspTraceCollector.isEnabled());
    }

    public void addInstallerListener(InstallerListener listener) {
        extensionRegistry.addInstallerListener(listener);
    }

    public void removeInstallerListener(InstallerListener listener) {
        extensionRegistry.removeInstallerListener(listener);
    }

    public void fireOnInstalled(ServerConfigBase config, InstallResult result) {
        extensionRegistry.fireOnInstalled(config, result);
    }

    void onStart(@Observes StartupEvent ignoredEv) {
        LOG.info("ApplicationManager starting...");

        // Load disabled state from settings.json
        loadDisabledState();

        // Initialize extension registry: deploy bundled configs + scan extensions/
        extensionRegistry.initialize(this);

        LOG.infof("Loaded %d LSP server descriptors", getLspServerConfigs().size());
        LOG.infof("Loaded %d DAP server descriptors", getDapServerConfigs().size());
        LOG.infof("Loaded %d BSP server descriptors", getBspServerConfigs().size());
    }

    private void loadDisabledState() {
        applicationConfiguration.migrateOldDisabledFormat();

        List<String> disabledExtensions = applicationConfiguration.getDisabledExtensionIds();
        if (!disabledExtensions.isEmpty()) {
            extensionRegistry.setDisabledExtensions(disabledExtensions);
        }
        List<String> disabledServers = applicationConfiguration.getDisabledServerIds();
        if (!disabledServers.isEmpty()) {
            extensionRegistry.setDisabledServers(disabledServers);
        }
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        LOG.info("Shutting down all workspaces...");
        shutdownAll().join();
    }

    /**
     * Get or create a workspace for the given root URI.
     * Workspace is created but NOT initialized (servers added on-demand).
     */
    public Workspace getOrCreateWorkspace(URI rootUri) {
        // Normalize URI
        URI workspaceUri = normalizeUri(rootUri);

        AtomicBoolean created = new AtomicBoolean();
        Workspace workspace = workspaces.computeIfAbsent(workspaceUri, uri -> {
            created.set(true);
            Workspace ws = new Workspace(uri, this);

            ws.setServerStatusChangeCallback(event -> {
                LOG.infof("WorkspaceManager: Firing server status change event: %s/%s - %s -> %s",
                        event.workspaceUri(), event.serverId(), event.oldStatus(), event.newStatus());
                serverStatusChangeEvent.fire(event);
            });

            LOG.infof("Created workspace %s", uri);
            ws.startFileWatcherIfEnabled();
            return ws;
        });

        // Clean up disconnected MCP clients before adding the new one
        cleanupDisconnectedMcpClients(workspace);

        // Add current MCP client to this workspace
        String clientName = mcpClientTracker.getCurrentClientName();
        String connectionId = mcpClientTracker.getCurrentConnectionId();

        LOG.infof("Adding MCP client to workspace %s: name=%s, connectionId=%s",
                workspaceUri, clientName, connectionId);

        boolean isNewClient = workspace.addMcpClient(connectionId, clientName);

        // Fire event if a new client was added
        if (isNewClient) {
            LOG.infof("New MCP client added to workspace, firing McpClientChangeEvent");
            mcpClientChangeEvent.fire(new McpClientChangeEvent());
        } else {
            LOG.infof("MCP client already exists in workspace, no event fired");
        }

        if (created.get()) {
            sendWorkspaceChangeEvent(WorkspaceChangeEvent.Type.CREATED, workspaceUri);
        }

        return workspace;
    }



    /**
     * Ensure LSP and DAP servers are running for the given file in the workspace.
     * Detects language, finds matching server configs, and starts them.
     */
    public CompletableFuture<Void> ensureServersForFile(URI fileUri,
                                                        Workspace workspace,
                                                        ProgressMonitor progressMonitor,
                                                        OperationContext operationContext) {
        Optional<String> languageId = languageRegistry.detectLanguage(fileUri);
        if (languageId.isEmpty()) {
            LOG.debugf("No language detected for: %s", fileUri);
            return CompletableFuture.completedFuture(null);
        }

        String language = languageId.get();
        LOG.debugf("Detected language '%s' for: %s", language, fileUri);

        Path basePath = workspace.getRootPath();
        List<LspServerConfig> configsToStart = new ArrayList<>();
        for (LspServerConfig config : extensionRegistry.getEnabledLspServerConfigs()) {
            if (config.isContributionOnly()) {
                continue;
            }
            if (config.canHandle(fileUri, language, basePath)) {
                if (!workspace.isServerActivated(config)) {
                    LOG.debugf("Server '%s' skipped: activation condition not met for workspace %s",
                            config.getServerId(), workspace.getNormalizedUri());
                    continue;
                }
                LspServer existingServer = workspace.getLspServer(config.getServerId());
                if (existingServer == null || existingServer.getStatus() == ServerStatus.STOPPED) {
                    configsToStart.add(config);
                }
            }
        }

        // Also find and add matching DAP servers (without starting them)
        for (DapServerConfig config : extensionRegistry.getEnabledDapServerConfigs()) {
            if (config.canHandle(fileUri, language, basePath)) {
                workspace.addDapServer(config);
            }
        }

        if (configsToStart.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        int count = configsToStart.size();
        List<CompletableFuture<Void>> serverFutures = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LspServerConfig config = configsToStart.get(i);
            LOG.infof("Need %s for language '%s' in workspace: %s",
                    config.getName(), language, workspace.getNormalizedUri());

            double start = (double) i / count;
            double end = (double) (i + 1) / count;
            ProgressMonitor serverMonitor = count > 1
                    ? progressMonitor.createSubMonitor(start, end)
                    : progressMonitor;

            String serverId = config.getServerId();
            OperationEntry serverEntry = operationContext.addEntry(serverId, serverId);
            CompletableFuture<Void> future = workspace.ensureLspServerReady(
                            serverId, serverMonitor, serverEntry)
                    .thenAccept(server -> {})
                    .exceptionally(ex -> {
                        LOG.errorf(ex, "Failed to start %s", config.getName());
                        serverEntry.fail(ToolException.resolveErrorMessage(ex));
                        return null;
                    });
            serverFutures.add(future);
        }

        return CompletableFuture.allOf(serverFutures.toArray(new CompletableFuture[0]));
    }

    /**
     * Ensure applicable LSP servers are started for the given workspace.
     * Used by workspace-level operations (e.g., workspace/symbol) that need
     * servers to be running without a specific file to trigger lazy startup.
     * <p>
     * Scans the workspace directory for files, detects their languages,
     * and starts only servers whose documentSelector matches a detected language.
     *
     * @param workspace       the workspace to start servers for
     * @param progressMonitor the progress monitor
     * @return a future that completes when all matching servers are started and ready
     */
    public CompletableFuture<Void> ensureServersForWorkspace(Workspace workspace,
                                                              ProgressMonitor progressMonitor) {
        Set<String> detectedLanguages = scanWorkspaceLanguages(workspace.getRootPath());
        if (detectedLanguages.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<LspServerConfig> configsToStart = new ArrayList<>();
        for (LspServerConfig config : extensionRegistry.getEnabledLspServerConfigs()) {
            if (config.isContributionOnly()) {
                continue;
            }
            if (!workspace.isServerActivated(config)) {
                continue;
            }
            LspServer existingServer = workspace.getLspServer(config.getServerId());
            if (existingServer != null && existingServer.getStatus() != ServerStatus.STOPPED) {
                continue;
            }
            if (config.getDocumentSelector() != null
                    && !Collections.disjoint(config.getDocumentSelector().getLanguages(), detectedLanguages)) {
                configsToStart.add(config);
            }
        }

        if (configsToStart.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Void>> serverFutures = new ArrayList<>();
        for (LspServerConfig config : configsToStart) {
            LOG.infof("Starting %s for workspace-level operation in: %s",
                    config.getName(), workspace.getNormalizedUri());

            CompletableFuture<Void> future = workspace.ensureLspServerReady(
                            config.getServerId(), progressMonitor)
                    .thenAccept(server -> {})
                    .exceptionally(ex -> {
                        LOG.errorf(ex, "Failed to start %s for workspace", config.getName());
                        return null;
                    });
            serverFutures.add(future);
        }

        return CompletableFuture.allOf(serverFutures.toArray(new CompletableFuture[0]));
    }

    /**
     * Scan the workspace directory for files and detect their languages.
     * Walks the directory tree up to depth 3, skipping hidden directories.
     *
     * @param rootPath the workspace root path
     * @return set of detected language identifiers
     */
    private Set<String> scanWorkspaceLanguages(Path rootPath) {
        Set<String> languages = new HashSet<>();
        try (var files = java.nio.file.Files.walk(rootPath, 3)) {
            files.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> !isHiddenPath(p, rootPath))
                    .forEach(file -> languageRegistry.detectLanguage(file.toUri())
                            .ifPresent(languages::add));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to scan workspace languages in: %s", rootPath);
        }
        return languages;
    }

    private static boolean isHiddenPath(Path file, Path root) {
        Path relative = root.relativize(file);
        for (Path segment : relative) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get or create workspace from a path (String cwd).
     * Converts the path to URI and creates/returns the workspace.
     *
     * @param cwd the workspace root path (e.g., "/home/user/project" or "C:\\Users\\project")
     * @return the workspace
     */
    public Workspace getWorkspaceForPath(String cwd) {
        URI workspaceUri = UriUtils.toUri(cwd);
        return getOrCreateWorkspace(workspaceUri);
    }

    /**
     * Shutdown all workspaces.
     */
    public CompletableFuture<Void> shutdownAll() {
        LOG.info("Shutting down all workspaces");

        CompletableFuture<?>[] shutdownFutures = workspaces.values().stream()
                .map(Workspace::shutdown)
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(shutdownFutures)
                .thenRun(() -> {
                    workspaces.clear();
                    LOG.info("All workspaces shut down");
                });
    }


    /**
     * Normalize URI (remove trailing slashes).
     */
    private URI normalizeUri(URI uri) {
        String uriStr = uri.toString();
        if (uriStr.endsWith("/")) {
            uriStr = uriStr.substring(0, uriStr.length() - 1);
        }
        return URI.create(uriStr);
    }

    public Workspace getWorkspace(URI uri) {
        return workspaces.get(uri);
    }

    /**
     * Get all active workspaces.
     */
    public Collection<Workspace> getWorkspaces() {
        return workspaces.values();
    }

    public void disableLspServer(String serverId) {
        extensionRegistry.disableLspServer(serverId);
        for (Workspace ws : getWorkspaces()) {
            LspServer server = ws.getLspServer(serverId);
            if (server != null && server.getStatus() != ServerStatus.STOPPED) {
                server.shutdown();
            }
        }
        serverEnabledChangeEvent.fire(new ServerEnabledChangeEvent(serverId, false));
    }

    public void enableLspServer(String serverId) {
        extensionRegistry.enableLspServer(serverId);
        serverEnabledChangeEvent.fire(new ServerEnabledChangeEvent(serverId, true));
    }

    public void disableDapServer(String serverId) {
        extensionRegistry.disableDapServer(serverId);
        serverEnabledChangeEvent.fire(new ServerEnabledChangeEvent(serverId, false));
    }

    public void enableDapServer(String serverId) {
        extensionRegistry.enableDapServer(serverId);
        serverEnabledChangeEvent.fire(new ServerEnabledChangeEvent(serverId, true));
    }

    public void disableBspServer(String serverId) {
        extensionRegistry.disableBspServer(serverId);
        for (Workspace ws : getWorkspaces()) {
            BspServer server = ws.getBspServer(serverId);
            if (server != null && server.getStatus() != ServerStatus.STOPPED) {
                server.shutdown();
            }
        }
        serverEnabledChangeEvent.fire(new ServerEnabledChangeEvent(serverId, false));
    }

    public void enableBspServer(String serverId) {
        extensionRegistry.enableBspServer(serverId);
        serverEnabledChangeEvent.fire(new ServerEnabledChangeEvent(serverId, true));
    }

    /**
     * Close a workspace: shutdown all its LSP servers and remove it from memory.
     */
    public CompletableFuture<Void> closeWorkspace(URI workspaceUri) {
        Workspace workspace = getWorkspace(workspaceUri);
        if (workspace == null) {
            LOG.warnf("Workspace not found: %s", workspaceUri);
            return CompletableFuture.completedFuture(null);
        }

        LOG.infof("Closing workspace: %s", workspaceUri);

        // Shutdown all servers in this workspace
        return workspace
                .shutdown()
                .thenRun(() -> {
                    // Remove from active workspaces
                    workspaces.remove(workspaceUri);
                    LOG.infof("Workspace closed and removed from memory: %s", workspaceUri);

                    // Fire workspace closed event
                    sendWorkspaceChangeEvent(WorkspaceChangeEvent.Type.CLOSED, workspaceUri);
                });
    }

    /**
     * Add server to workspace and start it (installation happens automatically in start()).
     */
    public CompletableFuture<Void> ensureServerStarted(String serverId, URI workspaceUri) {
        LspServerConfig config = getLspServerConfig(serverId);
        if (config == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown server: " + serverId));
        }

        Workspace workspace = getWorkspace(normalizeUri(workspaceUri));
        if (workspace == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Workspace not found: " + workspaceUri));
        }

        // Add server to workspace if not already present
        LspServer lspServer;
        if (!workspace.hasLspServer(serverId)) {
            lspServer = workspace.addLspServer(config);
        } else {
            lspServer = workspace.getLspServer(serverId);
        }

        // Start managed server with step-based progress (Installing → Starting → Initializing)
        String taskId = "start-" + serverId;
        String title = "Start " + serverId;
        TraceProgressMonitor progressMonitor = new TraceProgressMonitor(
                lspServer.getTraceCollector(), 100.0,
                progressBroadcasterInstance.isResolvable() ? progressBroadcasterInstance.get() : null,
                taskId, serverId, title);
        progressMonitor.addStep(ProgressStep.INSTALLING, 0.50);
        progressMonitor.addStep(ProgressStep.STARTING, 0.10);
        progressMonitor.addStep(ProgressStep.INITIALIZING, 0.40);
        progressMonitor.initializeSteps();

        return workspace.startManagedLspServer(serverId, progressMonitor)
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to start %s", config.getName());
                    progressMonitor.setFailed(ex.getMessage());
                    return null;
                });
    }

    public PathManager getPathManager() {
        return pathManager;
    }

    public Configuration getConfiguration() {
        return applicationConfiguration;
    }

    public List<IdeConfigurationProvider> getIdeConfigurationProviders() {
        List<String> ids = applicationConfiguration.getIdeConfigurationProviderIds();
        IdeConfigurationProviderRegistry registry = IdeConfigurationProviderRegistry.getInstance();
        List<IdeConfigurationProvider> providers = new ArrayList<>();
        for (String id : ids) {
            IdeConfigurationProvider provider = registry.getProvider(id);
            if (provider != null) {
                providers.add(provider);
            }
        }
        return providers;
    }

    public IdeConfigurationStrategy getIdeConfigurationStrategy() {
        return applicationConfiguration.getIdeConfigurationStrategy();
    }

    public ServerTrace getLspTraceLevel(String serverId) {
        return applicationConfiguration.getLspTraceLevel(serverId);
    }

    public ServerTrace getDapTraceLevel(String serverId) {
        return applicationConfiguration.getDapTraceLevel(serverId);
    }

    // LSP servers

    public LspServerConfig getLspServerConfig(String serverId) {
        return extensionRegistry.getLspServerConfig(serverId);
    }

    /**
     * Get all LSP server configurations.
     *
     * @return all LSP server configurations
     */
    public Collection<LspServerConfig> getLspServerConfigs() {
        return extensionRegistry.getAllLspServerConfigs();
    }

    public TraceCollector getLspTraceCollector() {
        return lspTraceCollector;
    }

    // DAP servers

    public DapServerConfig getDapServerConfig(String serverId) {
        return extensionRegistry.getDapServerConfig(serverId);
    }

    /**
     * Get all DAP server configurations.
     *
     * @return all DAP server configurations
     */
    public Collection<DapServerConfig> getDapServerConfigs() {
        return extensionRegistry.getAllDapServerConfigs();
    }

    // BSP servers

    public BspServerConfig getBspServerConfig(String serverId) {
        return extensionRegistry.getBspServerConfig(serverId);
    }

    /**
     * Get all BSP server configurations.
     *
     * @return all BSP server configurations
     */
    public Collection<BspServerConfig> getBspServerConfigs() {
        return extensionRegistry.getAllBspServerConfigs();
    }

    public TraceCollector getBspTraceCollector() {
        return bspTraceCollector;
    }

    public ServerTrace getBspTraceLevel(String serverId) {
        return applicationConfiguration.getBspTraceLevel(serverId);
    }

    public ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
    }

    public TraceCollector getDapTraceCollector() {
        return dapTraceCollector;
    }

    public McpTraceCollector getMcpTraceCollector() {
        return mcpTraceCollector;
    }

    private void sendWorkspaceChangeEvent(WorkspaceChangeEvent.Type type, URI workspaceUri) {
        workspaceChangeEvent.fire(new WorkspaceChangeEvent(type, workspaceUri));
    }

    public ContributionManager getContributionManager() {
        return contributionManager;
    }

    public ProgressBroadcaster getProgressBroadcaster() {
        return progressBroadcasterInstance.isResolvable() ? progressBroadcasterInstance.get() : null;
    }

    private void cleanupDisconnectedMcpClients(Workspace workspace) {
        var clients = workspace.getMcpClientConnections();
        if (clients.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String connectionId : clients.keySet()) {
            if (!connectionManager.has(connectionId)) {
                workspace.removeMcpClient(connectionId);
                changed = true;
            }
        }
        if (changed) {
            mcpClientChangeEvent.fire(new McpClientChangeEvent());
        }
    }
}

