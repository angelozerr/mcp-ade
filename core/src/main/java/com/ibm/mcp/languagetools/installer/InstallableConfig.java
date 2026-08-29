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
package com.ibm.mcp.languagetools.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.SharedProgressMonitor;
import com.ibm.mcp.languagetools.runtime.RuntimeConfig;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import org.jboss.logging.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Base class for installable components (servers and runtimes).
 * Provides identity, metadata, and the thread-safe installation lifecycle
 * (double-checked locking on a cached {@link CompletableFuture}).
 */
public abstract class InstallableConfig {

    private static final Logger LOG = Logger.getLogger(InstallableConfig.class);

    // --- Identity ---

    private final String serverId;
    private final Path serverHome;
    private final Extension extension;

    // --- Metadata ---

    private String name;
    private String description;
    private String url;
    private JsonElement installerConfig;
    private TraceCollector traceCollector;

    // --- Installation state ---

    private volatile ServerInstaller installer;
    private volatile CompletableFuture<InstallResult> installationFuture;
    private volatile SharedProgressMonitor sharedInstallProgress;
    private volatile String lastInstallError;
    private volatile RuntimeConfig installerRuntimeConfig;

    protected InstallableConfig(String serverId, Path serverHome, Extension extension) {
        this.serverId = serverId;
        this.serverHome = serverHome;
        this.extension = extension;
    }

    // --- Identity getters ---

    public String getServerId() {
        return serverId;
    }

    public Path getServerHome() {
        return serverHome;
    }

    public Extension getExtension() {
        return extension;
    }

    public String getExtensionId() {
        return extension != null ? extension.getId() : null;
    }

    public String getExtensionName() {
        return extension != null ? extension.getName() : null;
    }

    // --- Metadata getters / setters ---

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

    public JsonElement getInstallerConfig() {
        return installerConfig;
    }

    public void setInstallerConfig(JsonElement installerConfig) {
        this.installerConfig = installerConfig;
    }

    public TraceCollector getTraceCollector() {
        return traceCollector;
    }

    public void setTraceCollector(TraceCollector traceCollector) {
        this.traceCollector = traceCollector;
    }

    // --- Installer ---

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

    protected ServerInstaller createInstaller() {
        return installerConfig != null ? new TaskRegistryInstaller(this) : null;
    }

    // --- Installation status ---

    public InstallationStatus getStatus() {
        ServerInstaller inst = getInstaller();
        if (inst == null) {
            return InstallationStatus.NOT_INSTALLED;
        }
        InstallationStatus status = inst.getStatus();
        if (status == InstallationStatus.NOT_INSTALLED && isChecking()) {
            return InstallationStatus.CHECKING;
        }
        return status;
    }

    /**
     * Checks whether this component is installed without attempting to install it.
     * Thread-safe — only one check runs; result is cached.
     */
    public CompletableFuture<InstallResult> checkInstalled() {
        return executeCheck(() -> createInstallerContext(ProgressMonitor.none()));
    }

    public boolean isChecking() {
        CompletableFuture<InstallResult> future = installationFuture;
        return future != null && !future.isDone();
    }

    public String getLastInstallError() {
        return lastInstallError;
    }

    public SharedProgressMonitor getSharedInstallProgress() {
        return sharedInstallProgress;
    }

    public void resetInstallState() {
        synchronized (this) {
            CompletableFuture<InstallResult> future = installationFuture;
            if (future != null && future.isDone()) {
                installationFuture = null;
            }
        }
    }

    // --- Installer runtime dependency ---

    public String getInstallerRuntimeId() {
        if (installerConfig == null || !installerConfig.isJsonObject()) {
            return null;
        }
        JsonObject config = installerConfig.getAsJsonObject();
        if (config.has("runtime")) {
            JsonElement runtime = config.get("runtime");
            if (runtime.isJsonPrimitive()) {
                return runtime.getAsString();
            }
        }
        return null;
    }

    public RuntimeConfig getInstallerRuntimeConfig() {
        return installerRuntimeConfig;
    }

    public void setInstallerRuntimeConfig(RuntimeConfig config) {
        this.installerRuntimeConfig = config;
    }

    // --- InstallerContext factory ---

    protected InstallerContext createInstallerContext(ProgressMonitor progress) {
        return createInstallerContext(progress, null);
    }

    protected InstallerContext createInstallerContext(ProgressMonitor progress,
                                                      Consumer<InstallationStatus> statusChangeCallback) {
        return new InstallerContext(this, progress, statusChangeCallback);
    }

    // --- Installation lifecycle ---

    /**
     * Core template for thread-safe installation with double-checked locking.
     * Only one installation runs per component; subsequent callers share the same future.
     *
     * @param progressMonitor      progress listener for this caller
     * @param force                true to reset a completed installation and re-run
     * @param resetOnNotInstalled  true to also reset when status is NOT_INSTALLED (used by runtimes)
     * @param contextFactory       creates the {@link InstallerContext} (receives the shared progress monitor)
     * @return the shared installation future
     */
    protected CompletableFuture<InstallResult> executeInstallation(
            ProgressMonitor progressMonitor,
            boolean force,
            boolean resetOnNotInstalled,
            Function<SharedProgressMonitor, InstallerContext> contextFactory) {

        ServerInstaller inst = getInstaller();
        if (inst == null) {
            LOG.warnf("No installer for '%s'", serverId);
            return CompletableFuture.completedFuture(null);
        }

        LOG.infof("ensureInstalled called for '%s', force=%s, installationFuture=%s",
                serverId, force, installationFuture != null ? (installationFuture.isDone() ? "done" : "running") : "null");

        if (force || (resetOnNotInstalled && getStatus() == InstallationStatus.NOT_INSTALLED)) {
            synchronized (this) {
                CompletableFuture<InstallResult> existing = installationFuture;
                if (existing != null && (force || existing.isDone())) {
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
                    String taskId = "install-" + serverId;
                    sharedInstallProgress.startTask(taskId);

                    TraceProgressMonitor traceProgress = new TraceProgressMonitor(
                            traceCollector, 100.0, null, null, serverId, null);
                    onTraceProgressCreated(traceProgress);
                    sharedInstallProgress.addListener(traceProgress);

                    if (progressMonitor != ProgressMonitor.none()) {
                        sharedInstallProgress.addListener(progressMonitor);
                    }

                    InstallerContext context = contextFactory.apply(sharedInstallProgress);
                    context.setForceInstall(force);

                    final SharedProgressMonitor installProgress = sharedInstallProgress;
                    RuntimeConfig irtc = installerRuntimeConfig;
                    CompletableFuture<InstallResult> baseFuture;
                    if (irtc != null) {
                        baseFuture = irtc.ensureInstalled(ProgressMonitor.none())
                                .thenCompose(runtimeResult -> {
                                    addInstallerRuntimeToContextPath(context, irtc);
                                    return inst.ensureInstalled(context);
                                });
                    } else {
                        baseFuture = inst.ensureInstalled(context);
                    }
                    future = baseFuture
                            .whenComplete((result, error) -> {
                                installProgress.endTask(taskId);
                                synchronized (InstallableConfig.this) {
                                    if (sharedInstallProgress == installProgress) {
                                        sharedInstallProgress = null;
                                    }
                                }
                                if (error != null) {
                                    Throwable cause = error.getCause();
                                    lastInstallError = cause != null ? cause.getMessage() : error.getMessage();
                                    synchronized (InstallableConfig.this) {
                                        installationFuture = null;
                                    }
                                } else {
                                    lastInstallError = null;
                                    onInstallSuccess(result);
                                }
                            });
                    installationFuture = future;
                }
            }
        }

        if (!createdByThisCaller) {
            SharedProgressMonitor progress = sharedInstallProgress;
            if (progress != null && progressMonitor != ProgressMonitor.none()) {
                progress.addListener(progressMonitor);
            }
        }

        return future;
    }

    /**
     * Core template for thread-safe check-only (no installation).
     * Uses the same cached future as {@link #executeInstallation}.
     *
     * @param contextFactory creates the {@link InstallerContext}
     * @return the shared check future
     */
    protected CompletableFuture<InstallResult> executeCheck(Supplier<InstallerContext> contextFactory) {
        ServerInstaller inst = getInstaller();
        if (inst == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<InstallResult> future = installationFuture;
        if (future == null) {
            synchronized (this) {
                future = installationFuture;
                if (future == null) {
                    InstallerContext context = contextFactory.get();
                    future = inst.checkInstalled(context)
                            .whenComplete((result, error) -> {
                                if (error != null) {
                                    synchronized (InstallableConfig.this) {
                                        installationFuture = null;
                                    }
                                } else {
                                    lastInstallError = null;
                                    onInstallSuccess(result);
                                }
                            });
                    installationFuture = future;
                }
            }
        }
        return future;
    }

    // --- Hooks for subclasses ---

    protected void onTraceProgressCreated(TraceProgressMonitor traceProgress) {
    }

    protected void onInstallSuccess(InstallResult result) {
    }

    private static void addInstallerRuntimeToContextPath(InstallerContext context, RuntimeConfig installerRuntime) {
        String runtimeDir = null;
        String resolvedPath = installerRuntime.getResolvedPath();
        if (resolvedPath != null) {
            Path parentDir = Path.of(resolvedPath).getParent();
            if (parentDir != null) {
                runtimeDir = parentDir.toString();
            }
        }
        InstallerContext runtimeCtx = null;
        if (runtimeDir == null) {
            runtimeCtx = createRuntimeInstallerContext(installerRuntime);
            runtimeDir = TaskRegistryInstaller.extractCommandDir(installerRuntime.getInstallerConfig(), runtimeCtx);
        }
        if (runtimeDir == null) {
            return;
        }
        Map<String, String> env = context.getEnv();
        if (env == null) {
            env = new HashMap<>();
        }
        String currentPath = env.getOrDefault("PATH", System.getenv("PATH"));
        env.put("PATH", runtimeDir + File.pathSeparator + (currentPath != null ? currentPath : ""));
        addInstallerRuntimeEnv(env, installerRuntime, runtimeCtx);
        context.setEnv(env);
    }

    private static void addInstallerRuntimeEnv(Map<String, String> env, RuntimeConfig installerRuntime,
                                                InstallerContext runtimeCtx) {
        JsonElement installerConfig = installerRuntime.getInstallerConfig();
        if (installerConfig == null || !installerConfig.isJsonObject()) {
            return;
        }
        JsonElement envElement = installerConfig.getAsJsonObject().get("env");
        if (envElement == null || !envElement.isJsonObject()) {
            return;
        }
        if (runtimeCtx == null) {
            runtimeCtx = createRuntimeInstallerContext(installerRuntime);
        }
        for (var entry : envElement.getAsJsonObject().entrySet()) {
            env.put(entry.getKey(), runtimeCtx.resolveVariables(entry.getValue().getAsString()));
        }
    }

    private static InstallerContext createRuntimeInstallerContext(RuntimeConfig installerRuntime) {
        return new InstallerContext(installerRuntime, ProgressMonitor.none());
    }
}
