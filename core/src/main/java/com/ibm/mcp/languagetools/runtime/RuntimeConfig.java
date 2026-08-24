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
public class RuntimeConfig extends InstallableConfig {

    private static final Logger LOG = Logger.getLogger(RuntimeConfig.class);

    private volatile InstallerContext activeInstallerContext;

    private final List<ServerConfigBase> dependentServers = Collections.synchronizedList(new ArrayList<>());

    public RuntimeConfig(String runtimeId, Path runtimeHome, Extension extension) {
        super(runtimeId, runtimeHome, extension);
    }

    /**
     * Returns the runtime identifier (alias for getServerId).
     */
    public String getRuntimeId() {
        return getServerId();
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
        if (!Files.isDirectory(commandDirPath)) {
            return;
        }
        Map<String, String> env = new HashMap<>();
        String systemPath = System.getenv("PATH");
        env.put("PATH", commandDir + File.pathSeparator + (systemPath != null ? systemPath : ""));
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
    }

    /**
     * Checks whether this runtime is installed without attempting to install it.
     * Thread-safe — only one check runs; result is cached.
     */
    public CompletableFuture<InstallResult> checkInstalled(ProgressMonitor progressMonitor) {
        return executeCheck(() -> {
            InstallerContext context = new InstallerContext(this,
                    progressMonitor != ProgressMonitor.none() ? progressMonitor : ProgressMonitor.none());
            context.setVariable("MCP_HOME", getServerHome().getParent().getParent().toString());
            addInstalledRuntimeToEnv(context);
            return context;
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
                    InstallerContext context = new InstallerContext(this, progress);
                    context.setVariable("MCP_HOME", getServerHome().getParent().getParent().toString());
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
    }
}
