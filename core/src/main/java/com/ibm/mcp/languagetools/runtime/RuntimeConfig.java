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

import com.google.gson.JsonElement;
import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.installer.InstallableConfig;
import com.ibm.mcp.languagetools.installer.InstallResult;
import com.ibm.mcp.languagetools.installer.InstallationStatus;
import com.ibm.mcp.languagetools.installer.InstallerContext;
import com.ibm.mcp.languagetools.installer.ServerInstaller;
import com.ibm.mcp.languagetools.installer.TaskRegistryInstaller;
import com.ibm.mcp.languagetools.installer.TraceProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.SharedProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.trace.TraceCollector;
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

/**
 * Configuration for a runtime (JDK, Node.js, Go, etc.).
 * A runtime is an installable component that servers depend on.
 */
public class RuntimeConfig implements InstallableConfig {

    private static final Logger LOG = Logger.getLogger(RuntimeConfig.class);

    private final String runtimeId;
    private final Path runtimeHome;
    private final Extension extension;

    private String name;
    private String description;
    private String url;
    private JsonElement installerConfig;
    private TraceCollector traceCollector;

    private volatile ServerInstaller installer;
    private volatile CompletableFuture<InstallResult> installationFuture;
    private volatile SharedProgressMonitor sharedInstallProgress;
    private volatile String lastInstallError;

    private volatile InstallerContext activeInstallerContext;

    private final List<ServerConfigBase> dependentServers = Collections.synchronizedList(new ArrayList<>());

    public RuntimeConfig(String runtimeId, Path runtimeHome, Extension extension) {
        this.runtimeId = runtimeId;
        this.runtimeHome = runtimeHome;
        this.extension = extension;
    }

    @Override
    public String getServerId() {
        return runtimeId;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public Path getServerHome() {
        return runtimeHome;
    }

    @Override
    public JsonElement getInstallerConfig() {
        return installerConfig;
    }

    public void setInstallerConfig(JsonElement installerConfig) {
        this.installerConfig = installerConfig;
    }

    @Override
    public TraceCollector getTraceCollector() {
        return traceCollector;
    }

    public void setTraceCollector(TraceCollector traceCollector) {
        this.traceCollector = traceCollector;
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

    public Extension getExtension() {
        return extension;
    }

    public String getExtensionId() {
        return extension != null ? extension.getId() : null;
    }

    /**
     * Returns the runtime identifier (alias for getServerId).
     */
    public String getRuntimeId() {
        return runtimeId;
    }

    /**
     * Returns true if this runtime has an auto-installer (not check-only).
     */
    public boolean isAutoInstallable() {
        if (installerConfig == null || !installerConfig.isJsonObject()) {
            return false;
        }
        return installerConfig.getAsJsonObject().has("run");
    }

    // --- Dependent servers ---

    /**
     * Registers a server that depends on this runtime.
     */
    public void addDependentServer(ServerConfigBase server) {
        dependentServers.add(server);
    }

    /**
     * Returns all servers that depend on this runtime.
     */
    public List<ServerConfigBase> getDependentServers() {
        return new ArrayList<>(dependentServers);
    }

    // --- Installation ---

    /**
     * If the runtime's configured command directory already exists on disk (from a previous install),
     * add it to the context's env PATH so check tasks can find the runtime binary.
     * The directory is extracted from the configureServer command in installer.json.
     */
    private void addInstalledRuntimeToEnv(InstallerContext context) {
        String commandDir = TaskRegistryInstaller.extractCommandDir(installerConfig, context);
        if (commandDir == null) {
            return;
        }
        Path commandDirPath = Path.of(commandDir);
        if (!Files.isDirectory(commandDirPath)) {
            return;
        }
        Map<String, String> env = new HashMap<>();
        String systemPath = System.getenv("PATH");
        env.put("PATH", commandDir + File.pathSeparator + (systemPath != null ? systemPath : ""));
        context.setEnv(env);
    }

    public ServerInstaller getInstaller() {
        ServerInstaller inst = installer;
        if (inst == null && installerConfig != null) {
            synchronized (this) {
                inst = installer;
                if (inst == null) {
                    inst = new TaskRegistryInstaller(this);
                    installer = inst;
                }
            }
        }
        return inst;
    }

    public InstallationStatus getStatus() {
        ServerInstaller inst = getInstaller();
        if (inst != null) {
            return inst.getStatus();
        }
        return InstallationStatus.NOT_INSTALLED;
    }

    /**
     * Returns true if a check or install is currently in progress.
     */
    public boolean isChecking() {
        CompletableFuture<InstallResult> future = installationFuture;
        return future != null && !future.isDone();
    }

    public String getLastInstallError() {
        return lastInstallError;
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
                return "Runtime '" + name + "' is currently being installed. Please wait and retry.";
            case FAILED:
                if (isAutoInstallable()) {
                    return "Runtime '" + name + "' installation failed" +
                            (lastInstallError != null ? ": " + lastInstallError : "") +
                            ". You can retry the installation.";
                }
                return "Runtime '" + name + "' is not installed." +
                        (url != null ? " Install it manually from: " + url : "") +
                        ". Dependent servers cannot start until this runtime is available.";
            case NOT_INSTALLED:
            default:
                if (isAutoInstallable()) {
                    return "Runtime '" + name + "' will be auto-installed when the server starts.";
                }
                return "Runtime '" + name + "' is not installed." +
                        (url != null ? " Install it manually from: " + url : "") +
                        ". Dependent servers cannot start until this runtime is available.";
        }
    }

    /**
     * Populates runtime info into a server info map (used by list_language_servers and list_debug_adapters).
     */
    public void addRuntimeInfo(Map<String, Object> map) {
        map.put("runtimeStatus", getStatus().name());
        map.put("runtimeAutoInstallable", isAutoInstallable());
        if (url != null) {
            map.put("runtimeUrl", url);
        }
        if (lastInstallError != null) {
            map.put("runtimeError", lastInstallError);
        }
        String agentMessage = getAgentMessage();
        if (agentMessage != null) {
            map.put("runtimeMessage", agentMessage);
        }
    }

    /**
     * Reset installation state so the next ensureInstalled call starts fresh.
     * Only resets if the previous installation is already done (not in-progress).
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
     * Checks whether this runtime is installed without attempting to install it.
     * Returns ALREADY_INSTALLED or NOT_INSTALLED, never FAILED.
     * Thread-safe — only one check runs; result is cached like ensureInstalled.
     */
    public CompletableFuture<InstallResult> checkInstalled(ProgressMonitor progressMonitor) {
        ServerInstaller installer = getInstaller();
        if (installer == null) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<InstallResult> future = installationFuture;
        if (future == null) {
            synchronized (this) {
                future = installationFuture;
                if (future == null) {
                    InstallerContext context = new InstallerContext(this,
                            progressMonitor != ProgressMonitor.none() ? progressMonitor : ProgressMonitor.none());
                    context.setVariable("USER_HOME", runtimeHome.getParent().getParent().toString());
                    addInstalledRuntimeToEnv(context);

                    future = installer.checkInstalled(context)
                            .whenComplete((result, error) -> {
                                if (error != null) {
                                    synchronized (RuntimeConfig.this) {
                                        installationFuture = null;
                                    }
                                }
                                lastInstallError = null;
                            });
                    installationFuture = future;
                }
            }
        }
        return future;
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
        ServerInstaller installer = getInstaller();
        if (installer == null) {
            return CompletableFuture.completedFuture(null);
        }

        if (force || getStatus() == InstallationStatus.NOT_INSTALLED) {
            synchronized (this) {
                CompletableFuture<InstallResult> existing = installationFuture;
                if (existing != null && existing.isDone()) {
                    installationFuture = null;
                }
            }
        }

        boolean createdByThisCaller = false;
        CompletableFuture<InstallResult> future = installationFuture;
        if (future == null) {
            synchronized (this) {
                future = installationFuture;
                if (future == null) {
                    createdByThisCaller = true;
                    sharedInstallProgress = new SharedProgressMonitor();
                    String taskId = "install-runtime-" + runtimeId;
                    sharedInstallProgress.startTask(taskId);

                    TraceProgressMonitor traceProgress = new TraceProgressMonitor(traceCollector, 100.0,
                            null, null, runtimeId, null);
                    sharedInstallProgress.addListener(traceProgress);

                    if (progressMonitor != ProgressMonitor.none()) {
                        sharedInstallProgress.addListener(progressMonitor);
                    }

                    InstallerContext context = new InstallerContext(this, sharedInstallProgress);
                    context.setVariable("USER_HOME", runtimeHome.getParent().getParent().toString());
                    addInstalledRuntimeToEnv(context);
                    context.addParentTraceTarget(parentServerId, parentTraceCollector);
                    activeInstallerContext = context;

                    final SharedProgressMonitor installProgress = sharedInstallProgress;
                    future = installer.ensureInstalled(context)
                            .whenComplete((result, error) -> {
                                activeInstallerContext = null;
                                installProgress.endTask(taskId);
                                synchronized (RuntimeConfig.this) {
                                    if (sharedInstallProgress == installProgress) {
                                        sharedInstallProgress = null;
                                    }
                                }

                                if (error != null) {
                                    Throwable cause = error.getCause();
                                    lastInstallError = cause != null ? cause.getMessage() : error.getMessage();
                                    synchronized (RuntimeConfig.this) {
                                        installationFuture = null;
                                    }
                                } else {
                                    lastInstallError = null;
                                }
                            });
                    installationFuture = future;
                }
            }
        }

        // Subsequent callers only: register trace target + progress listener on existing install
        if (!createdByThisCaller) {
            InstallerContext ctx = activeInstallerContext;
            if (ctx != null) {
                ctx.addParentTraceTarget(parentServerId, parentTraceCollector);
            }
            SharedProgressMonitor progress = sharedInstallProgress;
            if (progress != null && progressMonitor != ProgressMonitor.none()) {
                progress.addListener(progressMonitor);
            }
        }

        return future;
    }
}
