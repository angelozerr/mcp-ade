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
import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.extension.ServerConfigSource;
import com.ibm.mcp.languagetools.installer.*;
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
 * Contains common fields: id, name, description, installer, documentSelector.
 */
public class ServerConfigBase implements ServerConfig {

    private static final Logger LOG = Logger.getLogger(ServerConfigBase.class);

    private final String serverId;
    private final Path serverHome;
    private final Extension extension;
    protected String name;
    protected String description;
    protected String url;
    protected JsonElement installerConfig;  // Raw JSON from installer.json
    protected DocumentSelector documentSelector;
    protected String command;
    protected Map<String, String> env = new HashMap<>();
    protected String workingDirectory;

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

    // Trace collector (set by workspace/session when server is added)
    protected TraceCollector traceCollector;

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

    public ServerConfigBase(String serverId, Path serverHome, Extension extension) {
        this.serverId = serverId;
        this.serverHome = serverHome;
        this.extension = extension;
    }

    // Common getters

    @Override
    public String getServerId() {
        return serverId;
    }

    @Override
    public Path getServerHome() {
        return serverHome;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public JsonElement getInstallerConfig() {
        return installerConfig;
    }

    public void setInstallerConfig(JsonElement installerConfig) {
        this.installerConfig = installerConfig;
        this.hasConfigureServer = detectConfigureServer(installerConfig);
    }

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

    public DocumentSelector getDocumentSelector() {
        return documentSelector;
    }

    public void setDocumentSelector(DocumentSelector documentSelector) {
        this.documentSelector = documentSelector;
    }

    public ActivationCondition getActivateWhen() {
        return activateWhen;
    }

    public void setActivateWhen(ActivationCondition activateWhen) {
        this.activateWhen = activateWhen;
    }

    public Contributes getContributes() {
        return contributes;
    }

    public boolean hasContributions() {
        return contributes != null;
    }

    public boolean isContributionOnly() {
        return !hasCommand() && hasContributions() && !hasConfigureServer();
    }

    public void setContributes(Contributes contributes) {
        this.contributes = contributes;
    }

    public void setAcceptContributions(List<String> acceptContributions) {
        this.acceptContributions = acceptContributions;
    }

    public boolean acceptsContribution(String contributionType) {
        return acceptContributions != null && acceptContributions.contains(contributionType);
    }

    public List<ServerSettingDescriptor> getSettings() {
        return settings;
    }

    public void setSettings(List<ServerSettingDescriptor> settings) {
        this.settings = settings;
    }

    public List<String> getApplicableSettings() {
        return applicableSettings;
    }

    public void setApplicableSettings(List<String> applicableSettings) {
        this.applicableSettings = applicableSettings;
    }

    public String getCommand() {
        return command;
    }

    public boolean hasCommand() {
        return command != null;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

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

    public Extension getExtension() {
        return extension;
    }

    public String getExtensionId() {
        return extension != null ? extension.getId() : null;
    }

    public ServerConfigSource getSource() {
        return extension != null ? extension.getSource() : null;
    }

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
     * @param pathManager          Path manager
     * @param serverStatusCallback Status callback
     * @param progressMonitor      Progress monitor (never null, use ProgressMonitor.none() if not available)
     */
    public CompletableFuture<InstallResult> ensureInstalled(PathManager pathManager,
                                                            Consumer<ServerStatus> serverStatusCallback,
                                                            ProgressMonitor progressMonitor) {
        return ensureInstalled(pathManager, serverStatusCallback, progressMonitor, false);
    }

    public CompletableFuture<InstallResult> ensureInstalled(PathManager pathManager,
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
                    InstallerContext context = createInstallerContext(pathManager, serverStatusCallback, force);

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

    private InstallerContext createInstallerContext(PathManager pathManager, Consumer<ServerStatus> serverStatusCallback, boolean force) {
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
        context.setVariable("USER_HOME", pathManager.getMcpLangToolsRoot().toString());
        context.setForceInstall(force);
        return context;
    }

}
