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
import com.ibm.mcp.languagetools.server.ServerVariables;
import com.ibm.mcp.languagetools.trace.TraceCollector;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}|\\$([A-Z_]+)\\$");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public InstallerContext(InstallableConfig config, ProgressMonitor progress) {
        this(config, progress, null);
    }

    public InstallerContext(InstallableConfig config, ProgressMonitor progress, Consumer<InstallationStatus> statusChangeCallback) {
        this.config = config;
        this.installDir = config.getServerHome();
        this.progress = progress;
        this.statusChangeCallback = statusChangeCallback;
        this.variables = new HashMap<>();

        ServerVariables.populate(config, variables);
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
     * Resolves variables in a template string.
     * Supports: ${variable} and $VARIABLE$
     */
    public String resolveVariables(String template) {
        if (template == null) {
            return null;
        }

        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String value = variables.get(varName);

            if (value != null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
            } else {
                // Keep original if variable not found
                matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(result);

        return result.toString();
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
    }

    private void traceInstallation(String message, TraceCollector.MessageType type) {
        TraceCollector tc = config.getTraceCollector();
        if (tc != null && tc.isEnabled()) {
            String formatted = String.format("[Installation - %s] %s",
                    TIME_FORMATTER.format(Instant.now()), message);
            tc.addTrace(config.getServerId(), formatted, type);
        }
    }
}
