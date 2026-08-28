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

import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.variable.VariableContext;
import com.ibm.mcp.languagetools.variable.VariableResolverRegistry;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Context for installation tasks.
 * Contains progress monitor, variables, and configuration.
 */
public class InstallerContext {
    private final ProgressMonitor progress;
    private final Map<String, String> variables;
    private final Path installDir;
    private final InstallableConfig config;
    private final Consumer<InstallationStatus> statusChangeCallback;

    private boolean forceInstall;
    private Map<String, String> env;
    private final List<ParentTraceTarget> parentTraceTargets = new CopyOnWriteArrayList<>();

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private record ParentTraceTarget(String contextId, TraceCollector traceCollector) {}

    public InstallerContext(InstallableConfig config, ProgressMonitor progress) {
        this(config, progress, null);
    }

    public InstallerContext(InstallableConfig config, ProgressMonitor progress, Consumer<InstallationStatus> statusChangeCallback) {
        this.config = config;
        this.installDir = config.getServerHome();
        this.progress = progress;
        this.statusChangeCallback = statusChangeCallback;
        this.variables = new HashMap<>();
    }

    /**
     * Notify installation status change if callback is registered.
     */
    public void notifyInstallationStatusChange(InstallationStatus installStatus) {
        if (statusChangeCallback != null) {
            statusChangeCallback.accept(installStatus);
        }
    }

    public ProgressMonitor getProgress() {
        return progress;
    }

    public Path getInstallDir() {
        return installDir;
    }

    public InstallableConfig getConfig() {
        return config;
    }

    /**
     * Sets a variable that can be used in templates.
     */
    public void setVariable(String key, String value) {
        variables.put(key, value);
    }

    /**
     * Gets a variable value.
     */
    public String getVariable(String key) {
        return variables.get(key);
    }

    /**
     * Resolves variables in a template string using the {@link VariableResolverRegistry}.
     */
    public String resolveVariables(String template) {
        if (template == null) {
            return null;
        }
        VariableContext ctx = new VariableContext.Builder()
                .serverConfig(config)
                .extraVariables(variables)
                .build();
        return VariableResolverRegistry.getInstance().resolve(template, ctx);
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    /**
     * Registers a parent server trace target so runtime installation traces
     * are also forwarded to the parent server's trace panel.
     * Thread-safe — can be called by subsequent callers while installation is in progress.
     */
    public void addParentTraceTarget(String contextId, TraceCollector traceCollector) {
        if (contextId != null && traceCollector != null) {
            parentTraceTargets.add(new ParentTraceTarget(contextId, traceCollector));
        }
    }

    public boolean isForceInstall() {
        return forceInstall;
    }

    public void setForceInstall(boolean forceInstall) {
        this.forceInstall = forceInstall;
    }

    /**
     * Checks if installation was cancelled.
     */
    public boolean isCanceled() {
        return progress.isCancelled();
    }

    /**
     * Throws exception if cancelled.
     */
    public void checkCanceled() {
        progress.checkCancelled();
    }

    public void traceInfo(String message) {
        traceInstallation(message, TraceCollector.MessageType.INFO);
    }

    public void traceError(String message) {
        traceInstallation(message, TraceCollector.MessageType.ERROR);
    }

    public void traceUpdate(String message) {
        TraceCollector tc = config.getTraceCollector();
        if (tc != null && tc.isEnabled()) {
            tc.addTrace(config.getServerId(), message, TraceCollector.MessageType.UPDATE);
        }
        traceToParents(message, TraceCollector.MessageType.UPDATE);
    }

    private void traceInstallation(String message, TraceCollector.MessageType type) {
        TraceCollector tc = config.getTraceCollector();
        String formatted = String.format("[Installation - %s] %s",
                TIME_FORMATTER.format(Instant.now()), message);
        if (tc != null && tc.isEnabled()) {
            tc.addTrace(config.getServerId(), formatted, type);
        }
        String parentFormatted = String.format("[Runtime - %s] %s",
                TIME_FORMATTER.format(Instant.now()), message);
        traceToParents(parentFormatted, type);
    }

    private void traceToParents(String message, TraceCollector.MessageType type) {
        for (ParentTraceTarget target : parentTraceTargets) {
            if (target.traceCollector.isEnabled()) {
                target.traceCollector.addTrace(target.contextId, message, type);
            }
        }
    }
}
