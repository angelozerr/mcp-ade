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
package org.eclipse.mcp.ade.runtime;

import org.eclipse.mcp.ade.extension.Extension;
import org.eclipse.mcp.ade.installer.InstallResult;
import org.eclipse.mcp.ade.installer.InstallableConfig;
import org.eclipse.mcp.ade.installer.InstallationStatus;
import org.eclipse.mcp.ade.installer.InstallerContext;
import org.eclipse.mcp.ade.installer.TaskRegistryInstaller;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import org.eclipse.mcp.ade.trace.TraceCollector;
import org.eclipse.mcp.ade.utils.OSUtils;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Configuration for a runtime (JDK, Node.js, Go, etc.).
 * A runtime is an installable component that servers depend on.
 */
public class RuntimeConfig extends InstallableConfig {

    private static final Logger LOG = Logger.getLogger(RuntimeConfig.class);

    private volatile InstallerContext activeInstallerContext;

    private final List<ServerConfigBase> dependentServers = Collections.synchronizedList(new ArrayList<>());

    // Runtime source resolution
    private volatile RuntimeSource sourceMode = RuntimeSource.AUTO;
    private volatile String resolvedPath;
    private volatile RuntimeSource activeSource = RuntimeSource.UNKNOWN;
    private volatile boolean fallbackUsed;
    private volatile ApplicationEnvironment applicationEnvironment;

    public RuntimeConfig(String runtimeId, Path runtimeHome, Extension extension) {
        super(runtimeId, runtimeHome, extension);
    }

    /**
     * Returns the runtime identifier (alias for getServerId).
     */
    public String getRuntimeId() {
        return getServerId();
    }

    @Override
    public InstallationStatus getStatus() {
        InstallationStatus status = super.getStatus();
        if (status == InstallationStatus.NOT_INSTALLED && resolvedPath != null) {
            return InstallationStatus.ALREADY_INSTALLED;
        }
        return status;
    }

    /**
     * Returns true if this runtime has an auto-installer (not check-only).
     */
    public boolean isAutoInstallable() {
        var config = getInstallerConfig();
        if (config == null || !config.isJsonObject()) {
            return false;
        }
        return config.getAsJsonObject().has("run");
    }

    // --- Dependent servers ---

    public void addDependentServer(ServerConfigBase server) {
        dependentServers.add(server);
    }

    public List<ServerConfigBase> getDependentServers() {
        return new ArrayList<>(dependentServers);
    }

    // --- Source mode ---

    public RuntimeSource getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(RuntimeSource sourceMode) {
        this.sourceMode = sourceMode;
        this.resolvedPath = null;
        this.activeSource = RuntimeSource.UNKNOWN;
        this.fallbackUsed = false;
    }

    public String getResolvedPath() {
        return resolvedPath;
    }

    public RuntimeSource getActiveSource() {
        return activeSource;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public void setApplicationEnvironment(ApplicationEnvironment applicationEnvironment) {
        this.applicationEnvironment = applicationEnvironment;
    }

    public ApplicationEnvironment getApplicationEnvironment() {
        return applicationEnvironment;
    }

    /**
     * Returns the application-level PATH that includes all installed runtimes.
     */
    public String getApplicationPath() {
        ApplicationEnvironment appEnv = applicationEnvironment;
        return appEnv != null ? appEnv.getPath() : null;
    }

    /**
     * Returns the directory containing the runtime binary,
     * derived from the resolved binary path.
     */
    public String getBinDirectory() {
        if (resolvedPath != null) {
            Path binDir = Path.of(resolvedPath).getParent();
            if (binDir != null) {
                return binDir.toString();
            }
        }
        return getServerHome().toString();
    }

    /**
     * Resolves the actual runtime binary path using which/where,
     * respecting the user's source preference.
     */
    private void resolveRuntimeBinaryPath() {
        String command = TaskRegistryInstaller.extractCheckCommand(getInstallerConfig());
        if (command == null) {
            resolvedPath = null;
            activeSource = RuntimeSource.UNKNOWN;
            fallbackUsed = false;
            return;
        }

        // Reset fallback flag before each resolution
        fallbackUsed = false;

        if (sourceMode == RuntimeSource.EMBEDDED) {
            resolveFromInstaller(command);
            return;
        }

        // AUTO or PATH: try system PATH first
        String pathResult = OSUtils.resolveCommandPath(command);
        if (pathResult != null) {
            resolvedPath = pathResult;
            activeSource = RuntimeSource.SYSTEM;
            return;
        }

        // Not found on PATH, try installer
        resolveFromInstaller(command);
        if (resolvedPath != null && sourceMode == RuntimeSource.SYSTEM) {
            fallbackUsed = true;
        }
    }

    private void resolveFromInstaller(String command) {
        InstallerContext tempContext = new InstallerContext(this, ProgressMonitor.none());
        String commandDir = TaskRegistryInstaller.extractCommandDir(getInstallerConfig(), tempContext);
        if (commandDir == null) {
            resolvedPath = null;
            activeSource = RuntimeSource.UNKNOWN;
            fallbackUsed = false;
            return;
        }

        Map<String, String> env = applicationEnvironment.createEnvWithPath(commandDir);
        String installerResult = OSUtils.resolveCommandPath(command, env);
        if (installerResult != null) {
            resolvedPath = installerResult;
            activeSource = RuntimeSource.EMBEDDED;
        } else {
            resolvedPath = null;
            activeSource = RuntimeSource.UNKNOWN;
            fallbackUsed = false;
        }
    }

    /**
     * Returns resolved env vars from installer.json "env" section (e.g. DOTNET_ROOT),
     * excluding PATH which is handled separately by {@link ServerConfigBase#addRuntimeToPath()}.
     */
    public Map<String, String> getResolvedEnv() {
        var installerConfig = getInstallerConfig();
        if (installerConfig == null || !installerConfig.isJsonObject()) {
            return Collections.emptyMap();
        }
        var envElement = installerConfig.getAsJsonObject().get("env");
        if (envElement == null || !envElement.isJsonObject()) {
            return Collections.emptyMap();
        }
        InstallerContext tempCtx = new InstallerContext(this, ProgressMonitor.none());
        Map<String, String> result = new HashMap<>();
        for (var entry : envElement.getAsJsonObject().entrySet()) {
            if (!"PATH".equals(entry.getKey())) {
                result.put(entry.getKey(), tempCtx.resolveVariables(entry.getValue().getAsString()));
            }
        }
        return result;
    }

    // --- Installation ---

    /**
     * If the runtime's configured command directory already exists on disk (from a previous install),
     * add it to the context's env PATH so check tasks can find the runtime binary.
     */
    private void addInstalledRuntimeToEnv(InstallerContext context) {
        String commandDir = TaskRegistryInstaller.extractCommandDir(getInstallerConfig(), context);
        if (commandDir == null) {
            return;
        }
        boolean dirExists = Files.isDirectory(Path.of(commandDir));
        if (!dirExists && sourceMode != RuntimeSource.EMBEDDED) {
            return;
        }

        if (dirExists) {
            context.setEnv(applicationEnvironment.createEnvWithPath(commandDir));
        } else {
            context.setEnv(applicationEnvironment.createEnvWithPath());
        }
    }

    /**
     * Returns a clear, actionable message for the AI agent about this runtime's status.
     * Returns null if the runtime is installed and no action is needed.
     */
    public String getAgentMessage() {
        InstallationStatus currentStatus = getStatus();
        switch (currentStatus) {
            case ALREADY_INSTALLED:
            case INSTALLED:
                return null;
            case INSTALLING:
                return "Runtime '" + getName() + "' is currently being installed. Please wait and retry.";
            case FAILED:
                if (isAutoInstallable()) {
                    return "Runtime '" + getName() + "' installation failed" +
                            (getLastInstallError() != null ? ": " + getLastInstallError() : "") +
                            ". You can retry the installation.";
                }
                return "Runtime '" + getName() + "' is not installed." +
                        (getUrl() != null ? " Install it manually from: " + getUrl() : "") +
                        ". Dependent servers cannot start until this runtime is available.";
            case NOT_INSTALLED:
            default:
                if (isAutoInstallable()) {
                    return "Runtime '" + getName() + "' will be auto-installed when the server starts.";
                }
                return "Runtime '" + getName() + "' is not installed." +
                        (getUrl() != null ? " Install it manually from: " + getUrl() : "") +
                        ". Dependent servers cannot start until this runtime is available.";
        }
    }

    /**
     * Populates runtime info into a server info map (used by list_language_servers and list_debug_adapters).
     */
    public void addRuntimeInfo(Map<String, Object> map) {
        map.put("runtimeStatus", getStatus().name());
        map.put("runtimeAutoInstallable", isAutoInstallable());
        if (getUrl() != null) {
            map.put("runtimeUrl", getUrl());
        }
        if (getLastInstallError() != null) {
            map.put("runtimeError", getLastInstallError());
        }
        String agentMessage = getAgentMessage();
        if (agentMessage != null) {
            map.put("runtimeMessage", agentMessage);
        }
        if (resolvedPath != null) {
            map.put("runtimeResolvedPath", resolvedPath);
        }
        map.put("runtimeActiveSource", activeSource.name());
    }

    /**
     * Checks whether this runtime is installed without attempting to install it.
     * Thread-safe — only one check runs; result is cached.
     */
    public CompletableFuture<InstallResult> checkInstalled(ProgressMonitor progressMonitor) {
        return executeCheck(() -> {
            InstallerContext context = createInstallerContext(progressMonitor);
            addInstalledRuntimeToEnv(context);
            return context;
        }).whenComplete((result, error) -> {
            if (error == null) {
                resolveRuntimeBinaryPath();
            }
        });
    }

    /**
     * Ensures the runtime is installed.
     * Thread-safe — only one installation runs even if called from multiple servers.
     */
    public CompletableFuture<InstallResult> ensureInstalled(ProgressMonitor progressMonitor) {
        return ensureInstalled(progressMonitor, false);
    }

    public CompletableFuture<InstallResult> ensureInstalled(ProgressMonitor progressMonitor, boolean force) {
        return ensureInstalled(progressMonitor, force, null, null);
    }

    /**
     * Ensures the runtime is installed, optionally forwarding traces to a parent server.
     * Thread-safe — only one installation runs even if called from multiple servers.
     * All callers' parent trace targets are registered on the shared InstallerContext.
     */
    public CompletableFuture<InstallResult> ensureInstalled(ProgressMonitor progressMonitor, boolean force,
                                                             String parentServerId, TraceCollector parentTraceCollector) {
        CompletableFuture<InstallResult> future = executeInstallation(
                progressMonitor, force, true,
                progress -> {
                    InstallerContext context = createInstallerContext(progress);
                    addInstalledRuntimeToEnv(context);
                    context.addParentTraceTarget(parentServerId, parentTraceCollector);
                    activeInstallerContext = context;
                    return context;
                });

        // For subsequent callers, also register parent trace target on existing context
        InstallerContext ctx = activeInstallerContext;
        if (ctx != null) {
            ctx.addParentTraceTarget(parentServerId, parentTraceCollector);
        }

        return future;
    }

    @Override
    protected void onInstallSuccess(InstallResult result) {
        activeInstallerContext = null;
        resolveRuntimeBinaryPath();
    }
}
