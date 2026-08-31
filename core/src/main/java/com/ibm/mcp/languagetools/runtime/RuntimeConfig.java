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
package com.ibm.mcp.languagetools.runtime;

import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.installer.InstallResult;
import com.ibm.mcp.languagetools.installer.InstallableConfig;
import com.ibm.mcp.languagetools.installer.InstallationStatus;
import com.ibm.mcp.languagetools.installer.InstallerContext;
import com.ibm.mcp.languagetools.installer.TaskRegistryInstaller;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.utils.OSUtils;
import org.jboss.logging.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Configuration for a runtime (JDK, Node.js, Go, etc.).
 * A runtime is an installable component that servers depend on.
 */
public class RuntimeConfig extends InstallableConfig {

    private static final Logger LOG = Logger.getLogger(RuntimeConfig.class);

    private volatile InstallerContext activeInstallerContext;

    private final List<ServerConfigBase> dependentServers = Collections.synchronizedList(new ArrayList<>());

    // Runtime source resolution
    private volatile RuntimeSourcePreference sourcePreference = RuntimeSourcePreference.AUTO;
    private volatile String resolvedPath;
    private volatile RuntimeSourceType activeSource = RuntimeSourceType.UNKNOWN;
    private volatile boolean fallbackUsed;
    private volatile Supplier<String> applicationPathSupplier;

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

    // --- Source preference ---

    public RuntimeSourcePreference getSourcePreference() {
        return sourcePreference;
    }

    public void setSourcePreference(RuntimeSourcePreference sourcePreference) {
        this.sourcePreference = sourcePreference;
    }

    public String getResolvedPath() {
        return resolvedPath;
    }

    public RuntimeSourceType getActiveSource() {
        return activeSource;
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public void setApplicationPathSupplier(Supplier<String> supplier) {
        this.applicationPathSupplier = supplier;
    }

    /**
     * Returns the application-level PATH that includes all installed runtimes,
     * or falls back to the system PATH if not yet available.
     */
    public String getApplicationPath() {
        Supplier<String> supplier = applicationPathSupplier;
        return supplier != null ? supplier.get() : System.getenv("PATH");
    }

    /**
     * Resolves the actual runtime binary path using which/where,
     * respecting the user's source preference.
     */
    private void resolveRuntimeBinaryPath() {
        String command = TaskRegistryInstaller.extractCheckCommand(getInstallerConfig());
        if (command == null) {
            resolvedPath = null;
            activeSource = RuntimeSourceType.UNKNOWN;
            fallbackUsed = false;
            return;
        }

        // Reset fallback flag before each resolution
        fallbackUsed = false;

        if (sourcePreference == RuntimeSourcePreference.INSTALLER) {
            resolveFromInstaller(command);
            return;
        }

        // AUTO or PATH: try system PATH first
        String pathResult = OSUtils.resolveCommandPath(command);
        if (pathResult != null) {
            resolvedPath = pathResult;
            activeSource = RuntimeSourceType.PATH;
            fallbackUsed = false;
            return;
        }

        // Not found on PATH, try installer
        resolveFromInstaller(command);
        if (resolvedPath != null && sourcePreference == RuntimeSourcePreference.PATH) {
            fallbackUsed = true;
        }
    }

    private void resolveFromInstaller(String command) {
        InstallerContext tempContext = new InstallerContext(this, ProgressMonitor.none());
        String commandDir = TaskRegistryInstaller.extractCommandDir(getInstallerConfig(), tempContext);
        if (commandDir == null) {
            resolvedPath = null;
            activeSource = RuntimeSourceType.UNKNOWN;
            fallbackUsed = false;
            return;
        }

        // Build env with installer dir on PATH and resolve
        Map<String, String> env = new HashMap<>();
        String basePath = getApplicationPath();
        env.put("PATH", commandDir + File.pathSeparator + (basePath != null ? basePath : ""));
        String installerResult = OSUtils.resolveCommandPath(command, env);
        if (installerResult != null) {
            resolvedPath = installerResult;
            activeSource = RuntimeSourceType.INSTALLER;
        } else {
            resolvedPath = null;
            activeSource = RuntimeSourceType.UNKNOWN;
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
        Path commandDirPath = Path.of(commandDir);
        boolean dirExists = Files.isDirectory(commandDirPath);

        String basePath = getApplicationPath();

        if (sourcePreference == RuntimeSourcePreference.INSTALLER) {
            Map<String, String> env = new HashMap<>();
            String path = dirExists ? commandDir + File.pathSeparator : "";
            env.put("PATH", path + (basePath != null ? basePath : ""));
            context.setEnv(env);
            return;
        }

        if (!dirExists) {
            return;
        }
        Map<String, String> env = new HashMap<>();
        env.put("PATH", commandDir + File.pathSeparator + (basePath != null ? basePath : ""));
        context.setEnv(env);
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
            InstallerContext context = createInstallerContext(
                    progressMonitor != ProgressMonitor.none() ? progressMonitor : ProgressMonitor.none());
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
