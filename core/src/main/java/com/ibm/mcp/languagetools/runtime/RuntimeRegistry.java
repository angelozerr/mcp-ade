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

import com.ibm.mcp.languagetools.configuration.ApplicationConfiguration;
import com.ibm.mcp.languagetools.installer.InstallableConfig;
import com.ibm.mcp.languagetools.installer.InstallationStatus;
import com.ibm.mcp.languagetools.installer.TraceProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for all runtime configurations.
 * Manages runtime lookup and server-to-runtime dependency wiring.
 */
@ApplicationScoped
public class RuntimeRegistry {

    private static final Logger LOG = Logger.getLogger(RuntimeRegistry.class);

    @Inject
    Event<RuntimeStatusChangeEvent> runtimeStatusEvent;

    @Inject
    ApplicationConfiguration applicationConfiguration;

    private final Map<String, RuntimeConfig> runtimes = new ConcurrentHashMap<>();
    private volatile TraceCollector traceCollector;

    /**
     * Sets the trace collector used by all runtimes for installation traces.
     */
    public void setTraceCollector(TraceCollector traceCollector) {
        this.traceCollector = traceCollector;
        for (RuntimeConfig runtime : runtimes.values()) {
            if (runtime.getTraceCollector() == null) {
                runtime.setTraceCollector(traceCollector);
            }
        }
    }

    /**
     * Registers a runtime configuration.
     */
    public void register(RuntimeConfig runtime) {
        if (traceCollector != null && runtime.getTraceCollector() == null) {
            runtime.setTraceCollector(traceCollector);
        }
        // Load persisted source preference
        RuntimeSourcePreference pref = applicationConfiguration.getRuntimeSourcePreference(runtime.getRuntimeId());
        runtime.setSourcePreference(pref);
        runtimes.put(runtime.getRuntimeId(), runtime);
        LOG.infof("Registered runtime: %s (%s), source preference: %s", runtime.getRuntimeId(), runtime.getName(), pref);
    }

    /**
     * Returns the runtime with the given id, or null if not found.
     */
    public RuntimeConfig get(String runtimeId) {
        return runtimes.get(runtimeId);
    }

    /**
     * Returns all registered runtimes.
     */
    public Map<String, RuntimeConfig> getAll() {
        return Collections.unmodifiableMap(runtimes);
    }

    /**
     * Checks all runtimes that haven't been checked yet.
     * Returns a future that completes when all checks are done.
     * Fires a CDI {@link RuntimeStatusChangeEvent} for each runtime when its check completes.
     */
    public CompletableFuture<Void> checkUnchecked() {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (RuntimeConfig runtime : runtimes.values()) {
            if (runtime.getStatus() != InstallationStatus.NOT_INSTALLED) {
                continue;
            }
            CompletableFuture<?> f = runtime.checkInstalled(ProgressMonitor.none())
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            LOG.debugf("Runtime check failed for '%s': %s", runtime.getRuntimeId(), error.getMessage());
                        } else {
                            LOG.infof("Runtime '%s' status: %s", runtime.getRuntimeId(),
                                    result != null ? result.getStatus() : "unknown");
                        }
                        fireStatusChange(runtime);
                    });
            futures.add(f);
        }
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Triggers an async check for a single runtime and fires a status event on completion.
     */
    public void checkRuntimeAsync(RuntimeConfig runtime) {
        runtime.checkInstalled(ProgressMonitor.none())
                .whenComplete((result, error) -> {
                    if (error != null) {
                        LOG.warnf("Runtime check failed for '%s': %s", runtime.getRuntimeId(), error.getMessage());
                    }
                    fireStatusChange(runtime);
                });
    }

    /**
     * Triggers a forced install for a single runtime and fires status events.
     */
    public void installRuntimeAsync(RuntimeConfig runtime) {
        installRuntimeAsync(runtime, null);
    }

    /**
     * Triggers a forced install for a single runtime with progress monitoring and fires status events.
     */
    public void installRuntimeAsync(RuntimeConfig runtime, TraceProgressMonitor progressMonitor) {
        fireStatusChange(runtime, InstallationStatus.INSTALLING);
        runtime.resetInstallState();
        ProgressMonitor monitor = progressMonitor != null ? progressMonitor : ProgressMonitor.none();
        runtime.ensureInstalled(monitor, true)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        LOG.errorf(error, "Failed to install runtime '%s'", runtime.getRuntimeId());
                        if (progressMonitor != null) {
                            Throwable cause = error.getCause() != null ? error.getCause() : error;
                            progressMonitor.setFailed(cause.getMessage());
                        }
                    } else {
                        LOG.infof("Runtime '%s' installed successfully", runtime.getRuntimeId());
                        if (progressMonitor != null) {
                            progressMonitor.setComplete();
                        }
                    }
                    fireStatusChange(runtime);
                });
    }

    /**
     * Changes the source preference for a runtime, persists it, and triggers a re-check.
     */
    public void setSourcePreference(String runtimeId, RuntimeSourcePreference pref) {
        RuntimeConfig runtime = runtimes.get(runtimeId);
        if (runtime == null) {
            return;
        }
        runtime.setSourcePreference(pref);
        applicationConfiguration.setRuntimeSourcePreference(runtimeId, pref);
        runtime.resetInstallState();
        checkRuntimeAsync(runtime);
    }

    private void fireStatusChange(RuntimeConfig runtime) {
        fireStatusChange(runtime, runtime.getStatus());
    }

    private void fireStatusChange(RuntimeConfig runtime, InstallationStatus status) {
        try {
            runtimeStatusEvent.fire(new RuntimeStatusChangeEvent(
                    runtime.getRuntimeId(),
                    status,
                    runtime.getLastInstallError(),
                    runtime.getResolvedPath(),
                    runtime.getActiveSource() != null ? runtime.getActiveSource().name() : null,
                    runtime.isFallbackUsed(),
                    runtime.getSourcePreference().name()));
        } catch (Exception e) {
            LOG.debugf(e, "Failed to fire runtime status event for '%s'", runtime.getRuntimeId());
        }
    }

    /**
     * Wires a server to its declared runtime.
     * Called after all runtimes and servers are loaded.
     */
    public void wireServer(ServerConfigBase server) {
        String runtimeId = server.getRuntime();
        if (runtimeId == null) {
            return;
        }
        RuntimeConfig runtime = runtimes.get(runtimeId);
        if (runtime != null) {
            runtime.addDependentServer(server);
            server.setRuntimeConfig(runtime);
            LOG.debugf("Wired server '%s' to runtime '%s'", server.getServerId(), runtimeId);
        } else {
            LOG.warnf("Server '%s' references unknown runtime '%s'", server.getServerId(), runtimeId);
        }
        wireInstallerRuntime(server);
    }

    /**
     * Wires all registered runtimes to their installer runtimes.
     * Must be called after all runtimes are registered.
     */
    public void wireAllInstallerRuntimes() {
        for (RuntimeConfig runtime : runtimes.values()) {
            wireInstallerRuntime(runtime);
        }
    }

    /**
     * Wires an installable config to the runtime declared in its installer.json.
     * This is independent of the server runtime — it's the runtime needed to run the installer commands.
     */
    public void wireInstallerRuntime(InstallableConfig config) {
        String installerRuntimeId = config.getInstallerRuntimeId();
        if (installerRuntimeId == null) {
            return;
        }
        RuntimeConfig runtime = runtimes.get(installerRuntimeId);
        if (runtime == config) {
            LOG.warnf("Installer of '%s' references itself as runtime, skipping to avoid circular dependency",
                    config.getServerId());
            return;
        }
        if (runtime != null) {
            config.setInstallerRuntimeConfig(runtime);
            LOG.debugf("Wired installer of '%s' to runtime '%s'", config.getServerId(), installerRuntimeId);
        } else {
            LOG.warnf("Installer of '%s' references unknown runtime '%s'", config.getServerId(), installerRuntimeId);
        }
    }
}
