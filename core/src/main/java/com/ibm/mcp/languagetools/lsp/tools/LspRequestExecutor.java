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


/**
 * Generic executor for LSP requests.
 * Handles the common pattern:
 * 1. Resolve LSP servers (via strategy)
 * 2. Execute LSP request on all servers in parallel
 * 3. Merge and format results
 *
 * @param <TRequestParams> Request parameters type (e.g., PositionBasedRequestParams, WorkspaceSymbolRequestParams)
 * @param <TLspParams>     LSP request parameters type (e.g., ReferenceParams, DefinitionParams)
 * @param <TResult>        LSP response type (e.g., List<Location>, List<LocationLink>)
 */
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
     * Execute an LSP request across all capable servers.
     *
     * @param params       Request parameters
     * @param strategy     Strategy for resolving servers, building params, executing request, and formatting results
     * @param cancellation Cancellation token
     * @param progress
     * @return Formatted result as String
     */
    public <TRequestParams extends LspRequestParams, TLspParams, TResult> CompletableFuture<String> execute(
            TRequestParams params,
            LspRequestStrategy<TRequestParams, TLspParams, TResult> strategy,
            Cancellation cancellation,
            Progress progress) {
        // Create progress monitor (MCP + Admin WebSocket contributors)
        ProgressMonitor progressMonitor = progressMonitorManager.createProgressMonitor(
                progress, cancellation, ProgressContext.forOperation(strategy.getCapability().name(), strategy.getTitle()));

        // Define steps for LSP operations
        progressMonitor
                .addStep(ProgressStep.INSTALLING, 0.40)
                .addStep(ProgressStep.STARTING, 0.10)
                .addStep(ProgressStep.INDEXING, 0.35)
                .addStep(ProgressStep.EXECUTING, 0.15);

        ProgressMonitor installMonitor = progressMonitor.beginStep(ProgressStep.INSTALLING);
        installMonitor.reportProgress(0.0, "Installing language server");

        OperationContext operationContext = operationTracker.startOperation(
                OperationTracker.resolveToolName(strategy.getTitle()), "tool", params.getCwd());
        operationContext.setArguments(params.toArgumentsMap());

        return strategy.resolveServers(serverResolver, params, installMonitor, operationContext)
                .thenCompose(servers -> {
                    if (servers.isEmpty()) {
                        return CompletableFuture.completedFuture(strategy.formatNoServerFound(params));
                    }

                    // Build server names for progress messages
                    String serverNames = servers.stream()
                            .map(s -> s.getConfig().getName())
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");

                    progressMonitor.beginStep(ProgressStep.STARTING);
                    ProgressMonitor indexingMonitor = progressMonitor.beginStep(ProgressStep.INDEXING);
                    indexingMonitor.reportProgress(0.0, "Indexing " + serverNames);

                    // Build LSP request parameters
                    TLspParams lspParams = strategy.buildLspParams(params);

                    // Execute LSP request on all servers in parallel (servers are already ready)
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

                    // Wait for all to complete and merge results
                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> {
                                List<TResult> results = futures.stream()
                                        .map(CompletableFuture::join)
                                        .filter(strategy::isValidResult)
                                        .toList();

                                if (results.isEmpty()) {
                                    return strategy.formatNoResultFound(params);
                                }

                                return strategy.formatResults(params, results);
                            });
                }).whenComplete((result, ex) -> {
                    progressMonitor.setComplete();
                    if (ex != null) {
                        operationContext.fail(ToolException.resolveErrorMessage(ex));
                    } else {
                        operationContext.setResult(result);
                        operationContext.complete();
                    }
                }).exceptionally(ToolException::rethrow);
    }

    /**
     * Strategy interface for customizing LSP request execution.
     *
     * @param <TRequestParams> Request parameters type
     * @param <TLspParams>     LSP request parameters type
     * @param <TResult>        LSP response type
     */
    public interface LspRequestStrategy<TRequestParams extends LspRequestParams, TLspParams, TResult> {

        /**
         * Get the LSP capability for this request.
         */
        LspCapability getCapability();

        /**
         * Get human-readable title for progress display.
         */
        default String getTitle() {
            return getCapability().name();
        }

        /**
         * Resolve LSP servers for this request.
         * Different requests resolve servers differently (file-based vs workspace-based).
         */
        CompletableFuture<List<LspServer>> resolveServers(
                LspServerResolver resolver,
                TRequestParams params, ProgressMonitor progressMonitor,
                OperationContext operationContext);

        /**
         * Build LSP request parameters from request params.
         */
        TLspParams buildLspParams(TRequestParams params);

        /**
         * Execute the LSP request on a server.
         */
        CompletableFuture<TResult> executeRequest(LspServer server, TLspParams lspParams);

        /**
         * Get empty result (used for failed requests).
         */
        TResult getEmptyResult();

        /**
         * Check if a result is valid (non-null, non-empty).
         */
        boolean isValidResult(TResult result);

        /**
         * Format results into a human-readable string.
         */
        String formatResults(TRequestParams params, List<TResult> results);

        /**
         * Format error message when no server is found.
         */
        default String formatNoServerFound(TRequestParams params) {
            return "[]";
        }

        /**
         * Format message when no results are found.
         */
        String formatNoResultFound(TRequestParams params);

        /**
         * Format error message.
         */
        default String formatError(TRequestParams params, Throwable ex) {
            return "Failed to execute request: " + ex.getMessage();
        }
    }
}

