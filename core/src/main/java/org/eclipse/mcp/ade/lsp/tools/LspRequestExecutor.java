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
package org.eclipse.mcp.ade.lsp.tools;

import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.lsp.client.LspCapability;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerResolver;
import org.eclipse.mcp.ade.lsp.tools.params.LspRequestParams;
import org.eclipse.mcp.ade.operation.OperationContext;
import org.eclipse.mcp.ade.operation.OperationEntry;
import org.eclipse.mcp.ade.operation.OperationTracker;
import org.eclipse.mcp.ade.progress.ProgressContext;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.progress.ProgressMonitorManager;
import org.eclipse.mcp.ade.progress.ProgressStep;
import org.eclipse.mcp.ade.tools.ToolException;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class LspRequestExecutor {

    private static final Logger LOG = Logger.getLogger(LspRequestExecutor.class);

    @Inject
    Application application;

    @Inject
    LspServerResolver serverResolver;

    @Inject
    ProgressMonitorManager progressMonitorManager;

    @Inject
    OperationTracker operationTracker;

    /**
     * Execute an LSP request across all capable servers and return typed results.
     *
     * @return raw results from all servers (filtered by {@link LspRequestStrategy#isValidResult})
     */
    public <TRequestParams extends LspRequestParams, TLspParams, TResult> CompletableFuture<List<TResult>> execute(
            TRequestParams params,
            LspRequestStrategy<TRequestParams, TLspParams, TResult> strategy,
            Cancellation cancellation,
            Progress progress) {

        OperationContext operationContext = createOperationContext(strategy, params);

        return doExecute(params, strategy, cancellation, progress, operationContext)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        operationContext.fail(ToolException.resolveErrorMessage(ex));
                    } else {
                        operationContext.complete();
                    }
                })
                .exceptionally(ToolException::rethrow);
    }

    /**
     * Execute an LSP request and return a formatted String result.
     *
     * <p>Delegates to {@link #doExecute}, applies the strategy's formatting,
     * and stores the formatted result in the {@link OperationContext} for display
     * in the MCP Activity panel.</p>
     */
    public <TRequestParams extends LspRequestParams, TLspParams, TResult> CompletableFuture<String> executeAsString(
            TRequestParams params,
            LspRequestStrategy<TRequestParams, TLspParams, TResult> strategy,
            Cancellation cancellation,
            Progress progress) {

        OperationContext operationContext = createOperationContext(strategy, params);

        return doExecute(params, strategy, cancellation, progress, operationContext)
                .thenApply(results -> {
                    if (results.isEmpty()) {
                        return strategy.formatNoResultFound(params);
                    }
                    return strategy.formatResults(params, results);
                })
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        operationContext.fail(ToolException.resolveErrorMessage(ex));
                    } else {
                        operationContext.setResult(result);
                        operationContext.complete();
                    }
                })
                .exceptionally(ToolException::rethrow);
    }

    /**
     * Create and initialize an {@link OperationContext} for the given strategy and params.
     */
    private <TRequestParams extends LspRequestParams> OperationContext createOperationContext(
            LspRequestStrategy<TRequestParams, ?, ?> strategy,
            TRequestParams params) {
        OperationContext operationContext = operationTracker.startOperation(
                OperationTracker.resolveToolName(strategy.getTitle()), "tool", params.getCwd());
        operationContext.setArguments(params.toArgumentsMap());
        return operationContext;
    }

    /**
     * Core execution logic: resolve servers, build LSP params, fan out requests,
     * and collect results. Handles progress monitoring but does NOT manage the
     * {@link OperationContext} lifecycle — callers are responsible for calling
     * {@link OperationContext#complete()} or {@link OperationContext#fail(String)}.
     */
    private <TRequestParams extends LspRequestParams, TLspParams, TResult> CompletableFuture<List<TResult>> doExecute(
            TRequestParams params,
            LspRequestStrategy<TRequestParams, TLspParams, TResult> strategy,
            Cancellation cancellation,
            Progress progress,
            OperationContext operationContext) {

        var workspace = application.getWorkspaceForPath(params.getCwd());
        if (workspace != null) {
            workspace.flushFileWatcher();
        }

        ProgressMonitor progressMonitor = progressMonitorManager.createProgressMonitor(
                progress, cancellation, ProgressContext.forOperation(strategy.getCapability().name(), strategy.getTitle()));

        progressMonitor
                .addStep(ProgressStep.INSTALLING_RUNTIME, 0.15)
                .addStep(ProgressStep.INSTALLING, 0.25)
                .addStep(ProgressStep.STARTING, 0.10)
                .addStep(ProgressStep.INDEXING, 0.35)
                .addStep(ProgressStep.EXECUTING, 0.15);

        progressMonitor.beginStep(ProgressStep.INSTALLING_RUNTIME);
        progressMonitor.reportProgress(0.0, "Installing language server");

        return strategy.resolveServers(serverResolver, params, progressMonitor, operationContext)
                .thenCompose(servers -> {
                    if (servers.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<TResult>of());
                    }

                    String serverNames = servers.stream()
                            .map(s -> s.getConfig().getName())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");

                    progressMonitor.beginStep(ProgressStep.STARTING);
                    ProgressMonitor indexingMonitor = progressMonitor.beginStep(ProgressStep.INDEXING);
                    indexingMonitor.reportProgress(0.0, "Indexing " + serverNames);

                    TLspParams lspParams = strategy.buildLspParams(params);

                    List<CompletableFuture<TResult>> futures = servers.stream()
                            .map(server -> {
                                String serverId = server.getConfig().getServerId();
                                String lspMethod = strategy.getCapability().getMethod();

                                OperationEntry serverEntry = operationContext.findEntryByServerId(serverId);
                                if (serverEntry == null) {
                                    serverEntry = operationContext.addEntry(serverId, serverId);
                                }
                                final OperationEntry parentEntry = serverEntry;

                                indexingMonitor.setComplete();
                                ProgressMonitor execMonitor = progressMonitor.beginStep(ProgressStep.EXECUTING);
                                execMonitor.reportProgress(0.0, "Executing " + strategy.getCapability().name().toLowerCase());

                                OperationEntry requestChild = parentEntry.addChild(lspMethod);
                                return progressMonitor.executeWithCancellation(strategy.executeRequest(server, lspParams))
                                        .thenApply(result -> {
                                            execMonitor.setComplete();
                                            requestChild.complete();
                                            parentEntry.complete();
                                            return result;
                                        })
                                        .exceptionally(ex -> {
                                            execMonitor.setComplete();
                                            String errorMessage = ToolException.resolveErrorMessage(ex);
                                            requestChild.fail(errorMessage);
                                            parentEntry.fail(errorMessage);
                                            LOG.warn("LSP request " + lspMethod + " failed on server " + serverId + ": " + errorMessage);
                                            return strategy.getEmptyResult();
                                        });
                            })
                            .toList();

                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> futures.stream()
                                    .map(CompletableFuture::join)
                                    .filter(strategy::isValidResult)
                                    .toList());
                })
                .whenComplete((result, ex) -> {
                    progressMonitor.setComplete();
                });
    }

    public interface LspRequestStrategy<TRequestParams extends LspRequestParams, TLspParams, TResult> {

        LspCapability getCapability();

        default String getTitle() {
            return getCapability().name();
        }

        CompletableFuture<List<LspServer>> resolveServers(
                LspServerResolver resolver,
                TRequestParams params, ProgressMonitor progressMonitor,
                OperationContext operationContext);

        TLspParams buildLspParams(TRequestParams params);

        CompletableFuture<TResult> executeRequest(LspServer server, TLspParams lspParams);

        TResult getEmptyResult();

        boolean isValidResult(TResult result);

        String formatResults(TRequestParams params, List<TResult> results);

        default String formatNoServerFound(TRequestParams params) {
            return "[]";
        }

        String formatNoResultFound(TRequestParams params);

        default String formatError(TRequestParams params, Throwable ex) {
            return "Failed to execute request: " + ex.getMessage();
        }
    }
}
