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
import com.ibm.mcp.languagetools.workspace.Workspace;
import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.extension.ServerConfigSource;
import com.ibm.mcp.languagetools.installer.InstallableConfig;
import com.ibm.mcp.languagetools.installer.InstallResult;
import com.ibm.mcp.languagetools.installer.InstallationStatus;
import com.ibm.mcp.languagetools.installer.InstallerContext;
import com.ibm.mcp.languagetools.installer.ServerInstaller;
import com.ibm.mcp.languagetools.installer.TraceProgressMonitor;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.language.DocumentSelector;
import com.ibm.mcp.languagetools.lsp.Contributes;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressStep;
import com.ibm.mcp.languagetools.runtime.RuntimeConfig;
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
public class ServerConfigBase extends InstallableConfig {

    private static final Logger LOG = Logger.getLogger(ServerConfigBase.class);

    private DocumentSelector documentSelector;
    private String command;
    private Map<String, String> env = new HashMap<>();
    private String workingDirectory;

    private String runtime;
    private RuntimeConfig runtimeConfig;
    private volatile boolean runtimePathAdded;

    private ActivationCondition activateWhen;

    private Contributes contributes;
    private List<String> acceptContributions;
    private List<ServerSettingDescriptor> settings;
    private List<String> applicableSettings;

    // Cached: whether the installer JSON contains a configureServer task
    private boolean hasConfigureServer;

    // Install progress monitor (set when installation starts, used by Admin UI)
    private TraceProgressMonitor installProgress;

    /**
     * Creates a server configuration with the given identity and extension.
     */
    public ServerConfigBase(String serverId, Path serverHome, Extension extension) {
        super(serverId, serverHome, extension);
    }

    // --- Runtime ---

    public String getRuntime() {
        return runtime;
    }

    public void setRuntime(String runtime) {
        this.runtime = runtime;
    }

    public RuntimeConfig getRuntimeConfig() {
        return runtimeConfig;
    }

    public void setRuntimeConfig(RuntimeConfig runtimeConfig) {
        this.runtimeConfig = runtimeConfig;
    }

    public String getRuntimeName() {
        return runtimeConfig != null ? runtimeConfig.getName() : null;
    }

    public String getRuntimeStatusName() {
        return runtimeConfig != null ? runtimeConfig.getStatus().name() : null;
    }

    private synchronized void addRuntimeToPath() {
        if (runtimePathAdded || runtimeConfig == null) {
            return;
        }

        String runtimeBin;
        // Use the resolved path from which/where if available
        if (runtimeConfig.getResolvedPath() != null) {
            java.nio.file.Path resolvedBinDir = java.nio.file.Path.of(runtimeConfig.getResolvedPath()).getParent();
            runtimeBin = resolvedBinDir != null ? resolvedBinDir.toString() : runtimeConfig.getServerHome().toString();
        } else {
            java.nio.file.Path runtimeHome = runtimeConfig.getServerHome();
            java.nio.file.Path binDir = runtimeHome.resolve("bin");
            if (java.nio.file.Files.isDirectory(binDir)) {
                runtimeBin = binDir.toString();
            } else {
                runtimeBin = runtimeHome.toString();
            }
        }

        String existingPath = env.get("PATH");
        if (existingPath != null) {
            env.put("PATH", runtimeBin + java.io.File.pathSeparator + existingPath);
        } else {
            String systemPath = System.getenv("PATH");
            env.put("PATH", runtimeBin + java.io.File.pathSeparator + (systemPath != null ? systemPath : ""));
        }
        env.putAll(runtimeConfig.getResolvedEnv());
        runtimePathAdded = true;
    }

    // --- Installer config (with configureServer detection) ---

    @Override
    public void setInstallerConfig(JsonElement installerConfig) {
        super.setInstallerConfig(installerConfig);
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

    // --- Installation status ---

    public void addInstallationStatus(Map<String, Object> serverInfo) {
        ServerInstaller inst = getInstaller();
        if (inst != null) {
            serverInfo.put("installationStatus", inst.getStatus().name());
        }
        if (getLastInstallError() != null) {
            serverInfo.putIfAbsent("error", getLastInstallError());
        }
    }

    // --- Document selector ---

    public DocumentSelector getDocumentSelector() {
        return documentSelector;
    }

    public void setDocumentSelector(DocumentSelector documentSelector) {
        this.documentSelector = documentSelector;
    }

    // --- Activation ---

    public ActivationCondition getActivateWhen() {
        return activateWhen;
    }

    public void setActivateWhen(ActivationCondition activateWhen) {
        this.activateWhen = activateWhen;
    }

    // --- Contributions ---

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

    // --- Settings ---

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

    // --- Command ---

    public String getCommand() {
        return command;
    }

    public boolean hasCommand() {
        return command != null;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    // --- Environment ---

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    // --- Working directory ---

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    // --- Document matching ---

    public boolean canHandle(URI uri, String language, Path basePath) {
        return documentSelector != null && documentSelector.matches(uri, language, basePath);
    }

    // --- Resource path ---

    public String getResourceBasePath() {
        Path serverHome = getServerHome();
        Path parent = serverHome.getParent();
        if (parent != null) {
            Path grandParent = parent.getParent();
            if (grandParent != null) {
                return "/" + parent.getFileName() + "/" + serverHome.getFileName();
            }
        }
        return "/lsp/" + getServerId();
    }

    // --- Install progress ---

    public TraceProgressMonitor getInstallProgress() {
        return installProgress;
    }

    public void setInstallProgress(TraceProgressMonitor installProgress) {
        this.installProgress = installProgress;
    }

    // --- Source ---

    public ServerConfigSource getSource() {
        Extension ext = getExtension();
        return ext != null ? ext.getSource() : null;
    }

    public Application getApplication() {
        Extension ext = getExtension();
        return ext != null ? ext.getApplication() : null;
    }

    // --- Installation lifecycle ---

    protected void onCommandInstalled(String command) {
        this.command = command;
    }

    @Override
    protected void onTraceProgressCreated(TraceProgressMonitor traceProgress) {
        setInstallProgress(traceProgress);
    }

    @Override
    protected void onInstallSuccess(InstallResult result) {
        if (result != null && result.getCommand() != null) {
            onCommandInstalled(result.getCommand());
        }
        if (getApplication() != null) {
            getApplication().fireOnInstalled(this, result);
        }
    }

    /**
     * Ensure server is installed.
     * Thread-safe — only one installation runs even if called from multiple workspaces.
     * Installs runtime first if needed, then the server itself.
     */
    public CompletableFuture<InstallResult> ensureInstalled(Workspace workspace,
                                                            Consumer<ServerStatus> serverStatusCallback,
                                                            ProgressMonitor progressMonitor) {
        return ensureInstalled(workspace, serverStatusCallback, progressMonitor, false, null);
    }

    public CompletableFuture<InstallResult> ensureInstalled(Workspace workspace,
                                                            Consumer<ServerStatus> serverStatusCallback,
                                                            ProgressMonitor progressMonitor,
                                                            boolean force) {
        return ensureInstalled(workspace, serverStatusCallback, progressMonitor, force, null);
    }

    /**
     * Ensure server is installed, tracking progress via OperationEntry.
     * When a runtime is configured, creates two separate children on the operationEntry:
     * one for the runtime installation and one for the server installation.
     * When no runtime is configured, creates a single "installing" child.
     *
     * @param operationEntry optional parent entry for operation tracking (nullable)
     */
    public CompletableFuture<InstallResult> ensureInstalled(Workspace workspace,
                                                            Consumer<ServerStatus> serverStatusCallback,
                                                            ProgressMonitor progressMonitor,
                                                            boolean force,
                                                            OperationEntry operationEntry) {
        if (runtimeConfig != null) {
            if (runtimeConfig.getTraceCollector() == null && getTraceCollector() != null) {
                runtimeConfig.setTraceCollector(getTraceCollector());
            }
            OperationEntry runtimeEntry = operationEntry != null
                    ? operationEntry.addChild("installing " + runtimeConfig.getName()) : null;
            return runtimeConfig.ensureInstalled(progressMonitor, false, getServerId(), getTraceCollector())
                    .whenComplete((result, error) -> {
                        if (runtimeEntry != null) {
                            if (error != null) {
                                runtimeEntry.fail(error.getMessage());
                            } else {
                                runtimeEntry.complete();
                            }
                        }
                    })
                    .thenCompose(runtimeResult -> {
                        addRuntimeToPath();
                        progressMonitor.beginStep("Installing " + getServerId());
                        progressMonitor.beginStep(ProgressStep.INSTALLING);
                        OperationEntry serverEntry = operationEntry != null
                                ? operationEntry.addChild("installing " + getName()) : null;
                        return doEnsureInstalled(workspace, serverStatusCallback, progressMonitor, force)
                                .whenComplete((result, error) -> {
                                    if (serverEntry != null) {
                                        if (error != null) {
                                            serverEntry.fail(error.getMessage());
                                        } else {
                                            serverEntry.complete();
                                        }
                                    }
                                });
                    });
        }
        OperationEntry installEntry = operationEntry != null
                ? operationEntry.addChild("installing") : null;
        return doEnsureInstalled(workspace, serverStatusCallback, progressMonitor, force)
                .whenComplete((result, error) -> {
                    if (installEntry != null) {
                        if (error != null) {
                            installEntry.fail(error.getMessage());
                        } else {
                            installEntry.complete();
                        }
                    }
                });
    }

    private CompletableFuture<InstallResult> doEnsureInstalled(Workspace workspace,
                                                                Consumer<ServerStatus> serverStatusCallback,
                                                                ProgressMonitor progressMonitor,
                                                                boolean force) {
        return executeInstallation(progressMonitor, force, false,
                progress -> createInstallerContext(workspace, serverStatusCallback, progress));
    }

    private InstallerContext createInstallerContext(Workspace workspace,
                                                    Consumer<ServerStatus> serverStatusCallback,
                                                    ProgressMonitor progress) {
        Consumer<InstallationStatus> installStatusCallback = installStatus -> {
            ServerStatus serverStatus = switch (installStatus) {
                case INSTALLING -> ServerStatus.INSTALLING;
                case INSTALLED, ALREADY_INSTALLED -> ServerStatus.STARTING;
                case FAILED -> ServerStatus.INSTALL_FAILED;
                default -> null;
            };
            if (serverStatus != null && serverStatusCallback != null) {
                serverStatusCallback.accept(serverStatus);
            }
        };

        InstallerContext context = createInstallerContext(progress, installStatusCallback);
        if (workspace != null) {
            context.setVariable("workspaceFolder", workspace.getRootPath().toString());
        }
        if (env != null && !env.isEmpty()) {
            context.setEnv(env);
        }
        return context;
    }
}
