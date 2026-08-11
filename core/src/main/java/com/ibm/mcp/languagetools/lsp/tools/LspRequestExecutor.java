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
package com.ibm.mcp.languagetools.lsp.tools;

import com.ibm.mcp.languagetools.lsp.client.LspCapability;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.server.LspServerResolver;
import com.ibm.mcp.languagetools.lsp.tools.params.LspRequestParams;
import com.ibm.mcp.languagetools.operation.OperationContext;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.operation.OperationTracker;
import com.ibm.mcp.languagetools.progress.ProgressContext;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressMonitorManager;
import com.ibm.mcp.languagetools.progress.ProgressStep;
import com.ibm.mcp.languagetools.tools.ToolException;
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
    LspServerResolver serverResolver;

    @Inject
    ProgressMonitorManager progressMonitorManager;

    @Inject
    OperationTracker operationTracker;

    /**
     * Execute an LSP request across all capable servers and return typed results.
     *
     * @return Raw results from all servers (filtered by {@link LspRequestStrategy#isValidResult})
     */
    public <TRequestParams extends LspRequestParams, TLspParams, TResult> CompletableFuture<List<TResult>> execute(
            TRequestParams params,
            LspRequestStrategy<TRequestParams, TLspParams, TResult> strategy,
            Cancellation cancellation,
            Progress progress) {

        OperationContext operationContext = operationTracker.startOperation(
                OperationTracker.resolveToolName(strategy.getTitle()), "tool", params.getCwd());
        operationContext.setArguments(params.toArgumentsMap());

        ProgressMonitor progressMonitor = progressMonitorManager.createProgressMonitor(
                progress, cancellation, ProgressContext.forOperation(strategy.getCapability().name(), strategy.getTitle()));

        progressMonitor
                .addStep(ProgressStep.INSTALLING, 0.40)
                .addStep(ProgressStep.STARTING, 0.10)
                .addStep(ProgressStep.INDEXING, 0.35)
                .addStep(ProgressStep.EXECUTING, 0.15);

        ProgressMonitor installMonitor = progressMonitor.beginStep(ProgressStep.INSTALLING);
        installMonitor.reportProgress(0.0, "Installing language server");

        return strategy.resolveServers(serverResolver, params, installMonitor, operationContext)
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
     * Delegates to {@link #execute} then applies the strategy's formatting.
     */
    public <TRequestParams extends LspRequestParams, TLspParams, TResult> CompletableFuture<String> executeAsString(
            TRequestParams params,
            LspRequestStrategy<TRequestParams, TLspParams, TResult> strategy,
            Cancellation cancellation,
            Progress progress) {
        return execute(params, strategy, cancellation, progress)
                .thenApply(results -> {
                    if (results.isEmpty()) {
                        return strategy.formatNoResultFound(params);
                    }
                    return strategy.formatResults(params, results);
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
