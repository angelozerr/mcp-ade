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
package com.ibm.mcp.languagetools.server;

import com.google.gson.JsonElement;
import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.PathManager;
import com.ibm.mcp.languagetools.workspace.Workspace;
import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.extension.ServerConfigSource;
import com.ibm.mcp.languagetools.installer.InstallResult;
import com.ibm.mcp.languagetools.installer.InstallationStatus;
import com.ibm.mcp.languagetools.installer.InstallerContext;
import com.ibm.mcp.languagetools.installer.ServerInstaller;
import com.ibm.mcp.languagetools.installer.TaskRegistryInstaller;
import com.ibm.mcp.languagetools.installer.TraceProgressMonitor;
import com.ibm.mcp.languagetools.language.DocumentSelector;
import com.ibm.mcp.languagetools.lsp.Contributes;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.SharedProgressMonitor;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Base class for server configurations (LSP and DAP).
 *
 * <p>Holds all configuration fields shared by both language servers ({@link com.ibm.mcp.languagetools.lsp.server.LspServerConfig})
 * and debug adapters ({@link com.ibm.mcp.languagetools.dap.server.DapServerConfig}):
 * identity (serverId, name, serverHome), execution (command, env, workingDirectory),
 * document matching (documentSelector), activation conditions, extension contributions,
 * declarative settings, and installer configuration.</p>
 *
 * <p>A single {@code ServerConfigBase} instance exists per registered server
 * (shared across all workspaces). Installation state ({@link #ensureInstalled})
 * is managed here so that only one installation runs even when multiple
 * workspaces request the same server simultaneously.</p>
 *
 * @see ServerBase
 */
public class ServerConfigBase {

    private static final Logger LOG = Logger.getLogger(ServerConfigBase.class);

    private final String serverId;
    private final Path serverHome;
    private final Extension extension;
    private String name;
    private String description;
    private String url;
    private JsonElement installerConfig;
    private DocumentSelector documentSelector;
    private String command;
    private Map<String, String> env = new HashMap<>();
    private String workingDirectory;

    private ActivationCondition activateWhen;

    /**
     * Contributions (VS Code-like extension system)
     */
    private Contributes contributes;

    /**
     * Contribution types this server accepts from other servers (e.g. ["classpath"], ["bundles"]).
     */
    private List<String> acceptContributions;

    /**
     * Declarative settings from server.json, rendered dynamically in the admin UI.
     */
    private List<ServerSettingDescriptor> settings;

    /**
     * Glob patterns for IDE settings keys applicable to this server (e.g. ["java.*"]).
     */
    private List<String> applicableSettings;

    private TraceCollector traceCollector;

    // Lazy-loaded installer instance
    private volatile ServerInstaller installer;

    // Cached: whether the installer JSON contains a configureServer task
    private boolean hasConfigureServer;

    // Install progress monitor (set when installation starts)
    private TraceProgressMonitor installProgress;

    // Shared progress monitor for installation (allows multiple listeners)
    private volatile SharedProgressMonitor sharedInstallProgress;

    // Installation state - shared across all workspaces
    private volatile CompletableFuture<InstallResult> installationFuture;
    private volatile String lastInstallError;

    /**
     * Creates a server configuration with the given identity and extension.
     *
     * @param serverId   unique server identifier (e.g. "jdtls")
     * @param serverHome installation directory for this server
     * @param extension  the extension that registered this server
     */
    public ServerConfigBase(String serverId, Path serverHome, Extension extension) {
        this.serverId = serverId;
        this.serverHome = serverHome;
        this.extension = extension;
    }

    // Common getters

    /**
     * Returns the unique server identifier (e.g. "jdtls", "java-debug").
     */
    public String getServerId() {
        return serverId;
    }

    /**
     * Returns the server installation directory.
     */
    public Path getServerHome() {
        return serverHome;
    }

    /**
     * Returns the human-readable server name.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the human-readable server name.
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the server description.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the server description.
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the server homepage URL.
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the server homepage URL.
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the installer configuration JSON from server.json.
     */
    public JsonElement getInstallerConfig() {
        return installerConfig;
    }

    /**
     * Sets the installer configuration JSON and detects if it contains a configureServer task.
     */
    public void setInstallerConfig(JsonElement installerConfig) {
        this.installerConfig = installerConfig;
        this.hasConfigureServer = detectConfigureServer(installerConfig);
    }

    /**
     * Returns {@code true} if the installer configuration contains a configureServer task.
     */
    public boolean hasConfigureServer() {
        return hasConfigureServer;
    }

    private static boolean detectConfigureServer(JsonElement installerConfig) {
        if (installerConfig == null || !installerConfig.isJsonObject()) {
            return false;
        }
        return containsConfigureServer(installerConfig.getAsJsonObject().get("run"));
    }

    private static boolean containsConfigureServer(JsonElement taskNode) {
        if (taskNode == null || !taskNode.isJsonObject()) {
            return false;
        }
        var taskObj = taskNode.getAsJsonObject();
        if (taskObj.has("configureServer")) {
            return true;
        }
        for (String key : taskObj.keySet()) {
            JsonElement child = taskObj.get(key);
            if (child != null && child.isJsonObject()) {
                var childObj = child.getAsJsonObject();
                if (childObj.has("onSuccess") && containsConfigureServer(childObj.get("onSuccess"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gets the installer instance (lazy-loaded).
     * Returns null if no installer configuration is present.
     */
    public ServerInstaller getInstaller() {
        ServerInstaller inst = installer;
        if (inst == null && installerConfig != null) {
            synchronized (this) {
                inst = installer;
                if (inst == null) {
                    inst = createInstaller();
                    installer = inst;
                }
            }
        }
        return inst;
    }

    /**
     * Add installation status and error info to a map (used by list_language_servers and list_debug_adapters).
     */
    public void addInstallationStatus(Map<String, Object> serverInfo) {
        ServerInstaller inst = getInstaller();
        if (inst != null) {
            serverInfo.put("installationStatus", inst.getStatus().name());
        }
        if (lastInstallError != null) {
            serverInfo.putIfAbsent("error", lastInstallError);
        }
    }

    /**
     * Creates the installer instance from configuration.
     * Override this method to use a different installer implementation.
     */
    protected ServerInstaller createInstaller() {
        if (installerConfig == null) {
            return null;
        }
        return new TaskRegistryInstaller(this);
    }

    /**
     * Gets the trace collector for this server.
     */
    public TraceCollector getTraceCollector() {
        return traceCollector;
    }

    /**
     * Sets the trace collector for this server.
     */
    public void setTraceCollector(TraceCollector traceCollector) {
        this.traceCollector = traceCollector;
    }

    /**
     * Returns the document selector for file matching.
     */
    public DocumentSelector getDocumentSelector() {
        return documentSelector;
    }

    /**
     * Sets the document selector for file matching.
     */
    public void setDocumentSelector(DocumentSelector documentSelector) {
        this.documentSelector = documentSelector;
    }

    /**
     * Returns the activation condition for this server.
     */
    public ActivationCondition getActivateWhen() {
        return activateWhen;
    }

    /**
     * Sets the activation condition for this server.
     */
    public void setActivateWhen(ActivationCondition activateWhen) {
        this.activateWhen = activateWhen;
    }

    /**
     * Returns the contributions declared by this server.
     */
    public Contributes getContributes() {
        return contributes;
    }

    /**
     * Returns {@code true} if this server declares contributions to other servers.
     */
    public boolean hasContributions() {
        return contributes != null;
    }

    /**
     * Returns {@code true} if this server only provides contributions (no own command).
     */
    public boolean isContributionOnly() {
        return !hasCommand() && hasContributions() && !hasConfigureServer();
    }

    /**
     * Sets the contributions declared by this server.
     */
    public void setContributes(Contributes contributes) {
        this.contributes = contributes;
    }

    /**
     * Sets the list of contribution types this server accepts from other servers.
     */
    public void setAcceptContributions(List<String> acceptContributions) {
        this.acceptContributions = acceptContributions;
    }

    /**
     * Returns {@code true} if this server accepts the given contribution type (e.g. "classpath", "bundles").
     */
    public boolean acceptsContribution(String contributionType) {
        return acceptContributions != null && acceptContributions.contains(contributionType);
    }

    /**
     * Returns the declarative settings descriptors from server.json.
     */
    public List<ServerSettingDescriptor> getSettings() {
        return settings;
    }

    /**
     * Sets the declarative settings descriptors.
     */
    public void setSettings(List<ServerSettingDescriptor> settings) {
        this.settings = settings;
    }

    /**
     * Returns the glob patterns for IDE settings keys applicable to this server.
     */
    public List<String> getApplicableSettings() {
        return applicableSettings;
    }

    /**
     * Sets the glob patterns for IDE settings keys applicable to this server.
     */
    public void setApplicableSettings(List<String> applicableSettings) {
        this.applicableSettings = applicableSettings;
    }

    /**
     * Returns the command line used to launch this server.
     */
    public String getCommand() {
        return command;
    }

    /**
     * Returns {@code true} if a launch command is configured.
     */
    public boolean hasCommand() {
        return command != null;
    }

    /**
     * Sets the command line used to launch this server.
     */
    public void setCommand(String command) {
        this.command = command;
    }

    /**
     * Returns the environment variables to set when launching the server.
     */
    public Map<String, String> getEnv() {
        return env;
    }

    /**
     * Sets the environment variables to set when launching the server.
     */
    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    /**
     * Returns the working directory for the server process.
     */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * Sets the working directory for the server process.
     */
    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Check if this server can handle the given file within a workspace.
     */
    public boolean canHandle(URI uri, String language, Path basePath) {
        return documentSelector != null && documentSelector.matches(uri, language, basePath);
    }

    /**
     * Get the resource base path for this server in the classpath.
     * For example: "/lsp/quarkus" for quarkus, "/dap/vscode-js-debug" for vscode-js-debug.
     * Derived from serverHome path structure.
     */
    public String getResourceBasePath() {
        // serverHome is like: /.../lsp/quarkus or /.../dap/vscode-js-debug
        // We extract the last 2 segments: lsp/quarkus or dap/vscode-js-debug
        Path parent = serverHome.getParent();  // lsp or dap
        if (parent != null) {
            Path grandParent = parent.getParent();
            if (grandParent != null) {
                return "/" + parent.getFileName() + "/" + serverHome.getFileName();
            }
        }
        // Fallback
        return "/lsp/" + serverId;
    }

    /**
     * Gets the install progress monitor (used to show visual progress bar in UI).
     */
    public TraceProgressMonitor getInstallProgress() {
        return installProgress;
    }

    /**
     * Gets the shared install progress monitor (used for cancellation from Admin UI).
     * Returns null if no installation is in progress.
     */
    public SharedProgressMonitor getSharedInstallProgress() {
        return sharedInstallProgress;
    }

    /**
     * Sets the install progress monitor (called when installation starts).
     */
    public void setInstallProgress(TraceProgressMonitor installProgress) {
        this.installProgress = installProgress;
    }

    /**
     * Returns the extension that registered this server.
     */
    public Extension getExtension() {
        return extension;
    }

    /**
     * Returns the extension identifier, or {@code null} if no extension.
     */
    public String getExtensionId() {
        return extension != null ? extension.getId() : null;
    }

    /**
     * Returns the source of this server configuration (built-in, user, etc.).
     */
    public ServerConfigSource getSource() {
        return extension != null ? extension.getSource() : null;
    }

    /**
     * Returns the application that owns this server's extension.
     */
    public Application getApplication() {
        return extension != null ? extension.getApplication() : null;
    }

    /**
     * Reset installation state so the next ensureInstalled call starts fresh.
     * Called from admin UI endpoints when the user explicitly requests an install.
     */
    public void resetInstallState() {
        synchronized (this) {
            CompletableFuture<InstallResult> future = installationFuture;
            if (future != null && future.isDone()) {
                installationFuture = null;
            }
        }
    }

    /**
     * Called when installation (or check) resolves a server command.
     * Subclasses can override to update their command field.
     */
    protected void onCommandInstalled(String command) {
        if (this.command == null) {
            this.command = command;
        }
    }

    /**
     * Ensure server is installed.
     * This method is thread-safe - only one installation will run even if called from multiple workspaces.
     * Returns a CompletableFuture that completes when installation is done.
     * If installation fails, the future is reset to null to allow retry.
     *
     * @param workspace             Workspace
     * @param serverStatusCallback Status callback
     * @param progressMonitor      Progress monitor (never null, use ProgressMonitor.none() if not available)
     */
    public CompletableFuture<InstallResult> ensureInstalled(Workspace workspace,
                                                            Consumer<ServerStatus> serverStatusCallback,
                                                            ProgressMonitor progressMonitor) {
        return ensureInstalled(workspace, serverStatusCallback, progressMonitor, false);
    }

    public CompletableFuture<InstallResult> ensureInstalled(Workspace workspace,
                                                            Consumer<ServerStatus> serverStatusCallback,
                                                            ProgressMonitor progressMonitor,
                                                            boolean force) {
        // progressMonitor must never be null - use ProgressMonitor.none() instead
        // If null, let it fail with NullPointerException to catch bugs early

        ServerInstaller installer = getInstaller();
        if (installer == null) {
            LOG.warnf("No installer for server '%s' (installerConfig=%s)", serverId, installerConfig != null ? "present" : "NULL");
            return CompletableFuture.completedFuture(null);
        }
        LOG.infof("ensureInstalled called for '%s', force=%s, installationFuture=%s",
                serverId, force, installationFuture != null ? (installationFuture.isDone() ? "done" : "running") : "null");

        // Force install: reset previous installation state
        if (force) {
            synchronized (this) {
                installationFuture = null;
            }
        }

        // Double-checked locking pattern
        CompletableFuture<InstallResult> future = installationFuture;
        if (future == null) {
            synchronized (this) {
                future = installationFuture;
                if (future == null) {
                    // FIRST caller - create SharedProgressMonitor for this installation
                    sharedInstallProgress = new SharedProgressMonitor();

                    // Create task ID for this installation
                    String taskId = "install-" + serverId;
                    sharedInstallProgress.startTask(taskId);

                    // Add TraceProgressMonitor for Admin UI
                    TraceProgressMonitor traceProgress = new TraceProgressMonitor(traceCollector, 100.0,
                            null, null, serverId, null);
                    setInstallProgress(traceProgress);
                    sharedInstallProgress.addListener(traceProgress);

                    // Add progress monitor from parameter (never null)
                    if (progressMonitor != ProgressMonitor.none()) {
                        sharedInstallProgress.addListener(progressMonitor);
                    }

                    // Map InstallationStatus to ServerStatus
                    InstallerContext context = createInstallerContext(workspace, serverStatusCallback, force);

                    final SharedProgressMonitor installProgress = sharedInstallProgress;
                    future = installer.ensureInstalled(context)
                            .whenComplete((result, error) -> {
                                installProgress.endTask(taskId);
                                synchronized (ServerConfigBase.this) {
                                    if (sharedInstallProgress == installProgress) {
                                        sharedInstallProgress = null;
                                    }
                                }

                                if (error != null) {
                                    Throwable cause = error.getCause();
                                    lastInstallError = cause != null ? cause.getMessage() : error.getMessage();
                                    synchronized (ServerConfigBase.this) {
                                        installationFuture = null;
                                    }
                                } else {
                                    lastInstallError = null;
                                    if (result != null && result.getCommand() != null) {
                                        onCommandInstalled(result.getCommand());
                                    }
                                    if (getApplication() != null) {
                                        getApplication().fireOnInstalled(ServerConfigBase.this, result);
                                    }
                                }
                            });
                    installationFuture = future;
                }
            }
        } else if (sharedInstallProgress != null) {
            // SUBSEQUENT callers - register as listener to get installation progress
            if (progressMonitor != ProgressMonitor.none()) {
                sharedInstallProgress.addListener(progressMonitor);
            }
        }
        return future;
    }

    private InstallerContext createInstallerContext(Workspace workspace, Consumer<ServerStatus> serverStatusCallback, boolean force) {
        Consumer<InstallationStatus> installStatusCallback = installStatus -> {
            ServerStatus serverStatus = switch (installStatus) {
                case INSTALLING -> ServerStatus.INSTALLING;
                case FAILED -> ServerStatus.INSTALL_FAILED;
                default -> null;
            };
            if (serverStatus != null && serverStatusCallback != null) {
                serverStatusCallback.accept(serverStatus);
            }
        };

        InstallerContext context = new InstallerContext(this, sharedInstallProgress, installStatusCallback);
        PathManager pathManager = workspace.getApplication().getPathManager();
        context.setVariable("USER_HOME", pathManager.getMcpLangToolsRoot().toString());
        context.setVariable("WORKSPACE_FOLDER", workspace.getRootPath().toString());
        context.setForceInstall(force);
        return context;
    }

}
