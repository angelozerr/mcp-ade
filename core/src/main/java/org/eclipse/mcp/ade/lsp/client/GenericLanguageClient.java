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
package org.eclipse.mcp.ade.lsp.client;

import org.eclipse.mcp.ade.progress.ProgressBroadcaster;
import org.eclipse.mcp.ade.server.ServerRequestRouter;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.utils.UriUtils;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Generic LSP client implementation with support for capability registration, bindRequest and bindNotification routing.
 * Extends {@link ServerRequestRouter} to handle bindRequest and bindNotification routing declared in server.json.
 * Implements Endpoint to handle custom requests.
 *
 * bindRequest: defaults to "executeCommand" mode (workspace/executeCommand)
 * bindNotification: defaults to "direct" mode (direct method call)
 */
public class GenericLanguageClient extends ServerRequestRouter implements LanguageClient, Endpoint {

    private static final Logger LOG = Logger.getLogger(GenericLanguageClient.class);

    protected final LspServer lspServer;

    private static final long DIAGNOSTICS_DEBOUNCE_MS = 300;

    private final Map<String, DiagnosticsWait> diagnosticsWaiters = new ConcurrentHashMap<>();
    private final ScheduledExecutorService diagnosticsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "diagnostics-debounce");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, LspProgressTask> activeProgresses = new ConcurrentHashMap<>();

    private static class DiagnosticsWait {
        final CompletableFuture<List<Diagnostic>> future = new CompletableFuture<>();
        volatile ScheduledFuture<?> timeoutHandle;
    }

    public GenericLanguageClient(LspServer lspServer) {
        super(lspServer.getConfig(), lspServer.getWorkspace());
        this.lspServer = lspServer;
    }

    @Override
    public void telemetryEvent(Object object) {
        // Ignore telemetry for now
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        String uri = UriUtils.normalizeUri(diagnostics.getUri());
        List<Diagnostic> diags = diagnostics.getDiagnostics();
        LOG.debugf("Diagnostics published for: %s (%d items)", uri, diags.size());

        // Don't clear cache when file is not opened
        // (server sends empty diagnostics after didClose)
        if (!diags.isEmpty() || lspServer.isFileOpened(uri)) {
            lspServer.getDiagnosticsCache().put(uri, diags);
        }

        DiagnosticsWait wait = diagnosticsWaiters.get(uri);
        if (wait != null && !wait.future.isDone()) {
            // Cancel previous timeout, reschedule with debounce delay
            ScheduledFuture<?> prev = wait.timeoutHandle;
            if (prev != null) {
                prev.cancel(false);
            }
            wait.timeoutHandle = diagnosticsScheduler.schedule(
                    () -> completeDiagnosticsWait(uri), DIAGNOSTICS_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    public CompletableFuture<List<Diagnostic>> waitForDiagnostics(String uri, long initialTimeoutMs) {
        String normalizedUri = UriUtils.normalizeUri(uri);
        DiagnosticsWait wait = new DiagnosticsWait();
        diagnosticsWaiters.put(normalizedUri, wait);
        wait.timeoutHandle = diagnosticsScheduler.schedule(
                () -> completeDiagnosticsWait(normalizedUri), initialTimeoutMs, TimeUnit.MILLISECONDS);
        return wait.future;
    }

    private void completeDiagnosticsWait(String uri) {
        DiagnosticsWait wait = diagnosticsWaiters.remove(uri);
        if (wait != null && !wait.future.isDone()) {
            List<Diagnostic> cached = lspServer.getDiagnosticsCache().get(uri);
            wait.future.complete(cached != null ? cached : Collections.emptyList());
        }
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        LOG.infof("%s message: %s", lspServer.getConfig().getServerId(), messageParams.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        LOG.infof("%s log: %s", lspServer.getConfig().getServerId(), message.getMessage());
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        LOG.infof("[%s] Registering capabilities", lspServer.getConfig().getServerId());
        lspServer.getClientFeatures().registerCapability(params);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        LOG.infof("[%s] Unregistering capabilities", lspServer.getConfig().getServerId());
        lspServer.getClientFeatures().unregisterCapability(params);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
        var workspaceConfig = lspServer.getWorkspace().getIdeConfiguration();
        var serverDefaults = lspServer.getConfig().getConfiguration();
        List<Object> results = new ArrayList<>();
        for (ConfigurationItem item : configurationParams.getItems()) {
            String convertedSection = convertConfigurationSection(item.getSection());
            ConfigurationItem convertedItem = new ConfigurationItem();
            convertedItem.setSection(convertedSection);
            convertedItem.setScopeUri(item.getScopeUri());
            Object result = workspaceConfig.find(convertedItem);
            if (result == null && serverDefaults != null) {
                result = findInDefaults(serverDefaults, convertedSection);
            }
            results.add(result);
        }
        return CompletableFuture.completedFuture(results);
    }

    /**
     * Finds a value in the server defaults map using direct key match
     * or flat key prefix matching (same logic as AbstractConfiguration.find()).
     */
    static Object findInDefaults(Map<String, Object> defaults, String section) {
        if (defaults.containsKey(section)) {
            return defaults.get(section);
        }
        String[] sectionParts = section.split("\\.");
        Map<String, Object> matched = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            String[] keyParts = key.split("\\.");
            if (sectionParts.length > keyParts.length) {
                continue;
            }
            boolean prefixMatch = true;
            for (int i = 0; i < sectionParts.length; i++) {
                if (!sectionParts[i].equals(keyParts[i])) {
                    prefixMatch = false;
                    break;
                }
            }
            if (prefixMatch) {
                matched.put(key, entry.getValue());
            }
        }
        return matched.isEmpty() ? null : matched;
    }

    /**
     * Converts a server-side configuration section name to the client-side configuration key.
     * Subclasses can override to handle server-specific naming conventions.
     */
    protected String convertConfigurationSection(String section) {
        return section;
    }

    @Override
    public CompletableFuture<Void> refreshDiagnostics() {
        LOG.infof("[%s] workspace/diagnostic/refresh received, re-pulling diagnostics for opened files",
                lspServer.getConfig().getServerId());
        lspServer.onDiagnosticRefresh();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
        String token = tokenToString(params.getToken());
        LOG.debugf("[%s] window/workDoneProgress/create received (token=%s)",
                lspServer.getConfig().getServerId(), token);
        activeProgresses.putIfAbsent(token, new LspProgressTask(token));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void notifyProgress(ProgressParams params) {
        if (params.getValue() == null || !params.getValue().isLeft()) {
            return;
        }

        String token = tokenToString(params.getToken());
        WorkDoneProgressNotification notification = params.getValue().getLeft();
        String serverId = lspServer.getConfig().getServerId();

        if (notification instanceof WorkDoneProgressBegin begin) {
            LspProgressTask task = activeProgresses.computeIfAbsent(token, LspProgressTask::new);
            task.title = begin.getTitle();
            task.cancellable = Boolean.TRUE.equals(begin.getCancellable());
            String taskId = task.getTaskId(serverId);

            LOG.debugf("[%s] $/progress begin: %s (token=%s, cancellable=%s)",
                    serverId, begin.getTitle(), token, task.cancellable);

            ProgressBroadcaster broadcaster = getBroadcaster();
            if (broadcaster != null) {
                if (task.cancellable) {
                    broadcaster.initTaskWithSteps(taskId, serverId, begin.getTitle(),
                            Collections.emptyList(), true);
                }
                double progress = begin.getPercentage() != null ? begin.getPercentage() / 100.0 : 0.0;
                broadcaster.taskRunning(taskId, serverId, begin.getTitle(), progress, begin.getMessage());
            }

        } else if (notification instanceof WorkDoneProgressReport report) {
            LspProgressTask task = activeProgresses.get(token);
            if (task == null) {
                return;
            }
            String taskId = task.getTaskId(serverId);

            if (report.getCancellable() != null) {
                boolean wasCancellable = task.cancellable;
                task.cancellable = report.getCancellable();
                ProgressBroadcaster broadcaster = getBroadcaster();
                if (broadcaster != null && task.cancellable != wasCancellable) {
                    broadcaster.initTaskWithSteps(taskId, serverId, task.title,
                            Collections.emptyList(), task.cancellable);
                }
            }

            LOG.debugf("[%s] $/progress report: %s %s%% (token=%s)",
                    serverId, report.getMessage(), report.getPercentage(), token);

            ProgressBroadcaster broadcaster = getBroadcaster();
            if (broadcaster != null) {
                double progress = report.getPercentage() != null ? report.getPercentage() / 100.0 : 0.0;
                broadcaster.taskRunning(taskId, serverId, task.title, progress, report.getMessage());
            }

        } else if (notification instanceof WorkDoneProgressEnd end) {
            LspProgressTask task = activeProgresses.remove(token);
            if (task == null) {
                return;
            }
            String taskId = task.getTaskId(serverId);

            LOG.debugf("[%s] $/progress end: %s (token=%s)", serverId, end.getMessage(), token);

            ProgressBroadcaster broadcaster = getBroadcaster();
            if (broadcaster != null) {
                broadcaster.taskCompleted(taskId, serverId, task.title);
            }
        }
    }

    private ProgressBroadcaster getBroadcaster() {
        return lspServer.getWorkspace().getApplication().getProgressBroadcaster();
    }

    private static String tokenToString(Either<String, Integer> token) {
        return token.isLeft() ? token.getLeft() : String.valueOf(token.getRight());
    }

    /**
     * Returns a snapshot of active LSP progress tasks for this server.
     */
    public Map<String, LspProgressTask> getActiveProgresses() {
        return Collections.unmodifiableMap(activeProgresses);
    }

    static class LspProgressTask {
        final String token;
        volatile String title;
        volatile boolean cancellable;

        LspProgressTask(String token) {
            this.token = token;
        }

        String getTaskId(String serverId) {
            return "lsp-progress-" + serverId + "-" + token;
        }
    }

    @Override
    public CompletableFuture<Void> refreshCodeLenses() {
        LOG.debugf("[%s] workspace/codeLens/refresh received", lspServer.getConfig().getServerId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshInlayHints() {
        LOG.debugf("[%s] workspace/inlayHint/refresh received", lspServer.getConfig().getServerId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> refreshSemanticTokens() {
        LOG.debugf("[%s] workspace/semanticTokens/refresh received", lspServer.getConfig().getServerId());
        return CompletableFuture.completedFuture(null);
    }

    public void shutdown() {
        diagnosticsScheduler.shutdownNow();
        cancelActiveProgresses();
    }

    private void cancelActiveProgresses() {
        if (activeProgresses.isEmpty()) {
            return;
        }
        ProgressBroadcaster broadcaster = getBroadcaster();
        String serverId = lspServer.getConfig().getServerId();
        for (LspProgressTask task : activeProgresses.values()) {
            if (broadcaster != null && task.title != null) {
                broadcaster.taskCompleted(task.getTaskId(serverId), serverId, task.title);
            }
        }
        activeProgresses.clear();
    }

    // -- Endpoint implementation (delegates to ServerRequestRouter) --

    @Override
    public CompletableFuture<?> request(String method, Object parameter) {
        return routeRequest(method, parameter);
    }

    @Override
    public void notify(String method, Object parameter) {
        routeNotification(method, parameter);
    }
}
