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

import com.ibm.mcp.languagetools.installer.InstallationStatus;
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
        runtimes.put(runtime.getRuntimeId(), runtime);
        LOG.infof("Registered runtime: %s (%s)", runtime.getRuntimeId(), runtime.getName());
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
        fireStatusChange(runtime, InstallationStatus.INSTALLING);
        runtime.ensureInstalled(ProgressMonitor.none(), true)
                .whenComplete((result, error) -> {
                    if (error != null) {
                        LOG.errorf(error, "Failed to install runtime '%s'", runtime.getRuntimeId());
                    } else {
                        LOG.infof("Runtime '%s' installed successfully", runtime.getRuntimeId());
                    }
                    fireStatusChange(runtime);
                });
    }

    private void fireStatusChange(RuntimeConfig runtime) {
        fireStatusChange(runtime, runtime.getStatus());
    }

    private void fireStatusChange(RuntimeConfig runtime, InstallationStatus status) {
        try {
            runtimeStatusEvent.fire(new RuntimeStatusChangeEvent(
                    runtime.getRuntimeId(),
                    status,
                    runtime.getLastInstallError()));
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
    }
}
