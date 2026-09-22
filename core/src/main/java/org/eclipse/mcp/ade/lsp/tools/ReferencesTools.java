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

import org.eclipse.mcp.ade.language.LanguageDocument;
import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.client.LspCapability;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerResolver;
import org.eclipse.mcp.ade.lsp.tools.params.FilePositionRequestParams;
import org.eclipse.mcp.ade.lsp.tools.strategies.LspJsonFormatter;
import org.eclipse.mcp.ade.lsp.tools.strategies.ReferencesStrategy;
import org.eclipse.mcp.ade.operation.OperationContext;
import org.eclipse.mcp.ade.operation.OperationTracker;
import org.eclipse.mcp.ade.progress.ProgressContext;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.progress.ProgressMonitorManager;
import org.eclipse.mcp.ade.progress.ProgressStep;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import org.eclipse.mcp.ade.tools.ToolException;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * MCP tools for LSP references (find all references).
 */
@ApplicationScoped
public class ReferencesTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LspServerResolver serverResolver;

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    SymbolNameResolver symbolNameResolver;

    @Inject
    ProgressMonitorManager progressMonitorManager;

    @Inject
    OperationTracker operationTracker;

    @Tool(
            name="find_references",
            description = "Find all references to a symbol across the workspace. " +
                        "Use symbolName (e.g. 'MyClass.myMethod') or uri+line+character.")
    public CompletableFuture<String> findReferences(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.SYMBOL_NAME, required = false) String symbolName,
            @ToolArg(description = ToolArgDescriptions.URI, required = false) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE, required = false) Integer line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER, required = false) Integer character,
            @ToolArg(description = ToolArgDescriptions.INCLUDE_ENCLOSING_SYMBOL, required = false) Boolean includeEnclosingSymbol,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {

        boolean enrich = includeEnclosingSymbol != null && includeEnclosingSymbol;

        return symbolNameResolver.resolveParams(cwd, symbolName, uri, line, character)
                .thenCompose(params -> {
                    if (!enrich) {
                        return requestExecutor.executeAsString(
                                params,
                                new ReferencesStrategy(languageRegistry),
                                cancellation,
                                progress);
                    }
                    return findReferencesWithContext(params, cancellation, progress);
                });
    }

    /**
     * Maximum number of concurrent documentSymbol requests during enrichment.
     * Prevents overwhelming the language server with hundreds of simultaneous
     * didOpen/documentSymbol/didClose cycles.
     */
    private static final int MAX_CONCURRENT_SYMBOL_REQUESTS = 5;

    private CompletableFuture<String> findReferencesWithContext(
            FilePositionRequestParams params, Cancellation cancellation, Progress progress) {

        OperationContext operationContext = operationTracker.startOperation(
                OperationTracker.resolveToolName("find_references"), "tool", params.getCwd());
        operationContext.setArguments(params.toArgumentsMap());

        ProgressMonitor progressMonitor = progressMonitorManager.createProgressMonitor(
                progress, cancellation, ProgressContext.forOperation("REFERENCES", "find_references"));

        progressMonitor
                .addStep(ProgressStep.INSTALLING_RUNTIME, 0.05)
                .addStep(ProgressStep.INSTALLING, 0.05)
                .addStep(ProgressStep.STARTING, 0.05)
                .addStep(ProgressStep.INDEXING, 0.10)
                .addStep(ProgressStep.EXECUTING, 0.15)
                .addStep(ProgressStep.ENRICHING, 0.60);

        progressMonitor.beginStep(ProgressStep.INSTALLING_RUNTIME);
        progressMonitor.reportProgress(0.0, "Finding references...");

        ReferencesStrategy strategy = new ReferencesStrategy(languageRegistry);
        long referencesStart = System.currentTimeMillis();

        return requestExecutor.executeWithMonitor(params, strategy, progressMonitor, operationContext)
                .thenCompose(results -> {
                    long referencesMs = System.currentTimeMillis() - referencesStart;
                    List<? extends Location> references = results.stream()
                            .flatMap(List::stream)
                            .distinct()
                            .toList();

                    if (references.isEmpty()) {
                        return CompletableFuture.completedFuture(LspJsonFormatter.EMPTY_ARRAY);
                    }

                    Map<String, List<Location>> byFile = references.stream()
                            .collect(Collectors.groupingBy(
                                    Location::getUri,
                                    LinkedHashMap::new,
                                    Collectors.toList()));

                    List<String> fileUris = new ArrayList<>(byFile.keySet());
                    Map<String, List<Either<SymbolInformation, DocumentSymbol>>> allSymbols = new ConcurrentHashMap<>();

                    int totalFiles = fileUris.size();
                    AtomicInteger completed = new AtomicInteger(0);

                    progressMonitor.beginStep(ProgressStep.ENRICHING);
                    progressMonitor.reportProgress(0.0,
                            "References found: " + references.size() + " in " + totalFiles
                                    + " files (" + referencesMs + "ms). Resolving enclosing symbols (0/" + totalFiles + ")...");

                    long enrichStart = System.currentTimeMillis();

                    Queue<CompletableFuture<?>> pendingFutures = new ConcurrentLinkedQueue<>();
                    progressMonitor.onCancelled(() -> {
                        for (CompletableFuture<?> f : pendingFutures) {
                            f.cancel(true);
                        }
                    });

                    return progressMonitor.executeWithCancellation(
                            fetchSymbolsInBatches(fileUris, 0, allSymbols, params.getCwd(),
                                    progressMonitor, completed, totalFiles, enrichStart, pendingFutures)
                                    .thenApply(v -> formatEnrichedReferences(references, allSymbols, params.getCwd())));
                })
                .handle((result, ex) -> {
                    if (ex != null) {
                        Throwable cause = ex instanceof java.util.concurrent.CompletionException
                                ? ex.getCause() : ex;
                        if (cause instanceof CancellationException) {
                            progressMonitor.setCancelled();
                        } else {
                            progressMonitor.setComplete();
                        }
                        operationContext.fail(ToolException.resolveErrorMessage(ex));
                        if (cause instanceof CancellationException) {
                            return "Operation cancelled";
                        }
                        return ToolException.rethrow(ex);
                    }
                    if (progressMonitor.isCancelled()) {
                        progressMonitor.setCancelled();
                        operationContext.fail("Operation cancelled");
                        return "Operation cancelled";
                    }
                    progressMonitor.setComplete();
                    operationContext.setResult(result);
                    operationContext.complete();
                    return result;
                });
    }

    private CompletableFuture<Void> fetchSymbolsInBatches(
            List<String> fileUris, int fromIndex,
            Map<String, List<Either<SymbolInformation, DocumentSymbol>>> allSymbols,
            String cwd,
            ProgressMonitor progressMonitor, AtomicInteger completed, int totalFiles,
            long enrichStart, Queue<CompletableFuture<?>> pendingFutures) {

        if (progressMonitor.isCancelled()) {
            return CompletableFuture.failedFuture(new CancellationException("Operation cancelled"));
        }

        if (fromIndex >= fileUris.size()) {
            return CompletableFuture.completedFuture(null);
        }

        int toIndex = Math.min(fromIndex + MAX_CONCURRENT_SYMBOL_REQUESTS, fileUris.size());

        List<CompletableFuture<Void>> batch = fileUris.subList(fromIndex, toIndex).stream()
                .map(fileUri -> {
                    if (progressMonitor.isCancelled()) {
                        return CompletableFuture.<Void>failedFuture(
                                new CancellationException("Operation cancelled"));
                    }
                    CompletableFuture<Void> future = fetchDocumentSymbols(cwd, fileUri, progressMonitor)
                            .thenAccept(symbols -> {
                                if (progressMonitor.isCancelled()) {
                                    throw new CancellationException("Operation cancelled");
                                }
                                allSymbols.put(fileUri, symbols);
                                int done = completed.incrementAndGet();
                                if (done == totalFiles || done % 20 == 0) {
                                    long elapsed = System.currentTimeMillis() - enrichStart;
                                    progressMonitor.reportProgress(
                                            100.0 * done / totalFiles,
                                            "Resolving enclosing symbols (" + done + "/" + totalFiles
                                                    + ") - " + elapsed + "ms");
                                }
                            })
                            .exceptionally(ex -> {
                                Throwable cause = ex instanceof java.util.concurrent.CompletionException
                                        ? ex.getCause() : ex;
                                if (cause instanceof CancellationException) {
                                    throw (CancellationException) cause;
                                }
                                return null;
                            });
                    pendingFutures.add(future);
                    return future;
                })
                .toList();

        CompletableFuture<Void> batchFuture = CompletableFuture.allOf(batch.toArray(new CompletableFuture[0]));
        pendingFutures.add(batchFuture);

        return batchFuture
                .thenCompose(v -> {
                    if (progressMonitor.isCancelled()) {
                        return CompletableFuture.failedFuture(
                                new CancellationException("Operation cancelled"));
                    }
                    return fetchSymbolsInBatches(fileUris, toIndex, allSymbols, cwd,
                            progressMonitor, completed, totalFiles, enrichStart, pendingFutures);
                });
    }

    private CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> fetchDocumentSymbols(
            String cwd, String fileUri, ProgressMonitor progressMonitor) {
        LanguageDocument document = languageRegistry.createDocument(fileUri);
        LspCapability capability = LspCapability.DOCUMENT_SYMBOL;
        return serverResolver.getLspServersForFile(
                        document, cwd,
                        server -> server.isEnabled() && server.supportsCapability(capability, document),
                        ProgressMonitor.none(), OperationContext.noop())
                .thenCompose(servers -> {
                    if (servers.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<Either<SymbolInformation, DocumentSymbol>>of());
                    }
                    LspServer server = servers.get(0);
                    DocumentSymbolParams lspParams = new DocumentSymbolParams();
                    lspParams.setTextDocument(new TextDocumentIdentifier(fileUri));
                    String languageId = languageRegistry.detectLanguage(java.net.URI.create(fileUri)).orElse("");
                    return server.withAutoDidOpen(capability, fileUri, languageId,
                            () -> server.getLanguageServer()
                                    .getTextDocumentService()
                                    .documentSymbol(lspParams),
                            progressMonitor);
                })
                .thenApply(result -> result != null ? result : List.of());
    }

    static String formatEnrichedReferences(
            List<? extends Location> references,
            Map<String, List<Either<SymbolInformation, DocumentSymbol>>> symbolsByFile,
            String cwd) {
        String cwdUri = LspJsonFormatter.cwdToUriPrefix(cwd);

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Location ref : references) {
            String fileUri = ref.getUri();
            String compactUri = LspJsonFormatter.compactUri(fileUri, cwdUri);

            Map<String, Object> refEntry = new LinkedHashMap<>();
            refEntry.put("range", LspJsonFormatter.range(ref.getRange()));

            List<Either<SymbolInformation, DocumentSymbol>> symbols = symbolsByFile.get(fileUri);
            if (symbols != null) {
                DocumentSymbol enclosing = EnclosingSymbolFinder.findEnclosing(symbols, ref.getRange().getStart());
                if (enclosing != null) {
                    refEntry.put("in", enclosing.getName());
                    if (enclosing.getKind() != null) {
                        refEntry.put("kind", enclosing.getKind().name());
                    }
                }
            }

            grouped.computeIfAbsent(compactUri, k -> new ArrayList<>()).add(refEntry);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            Map<String, Object> fileGroup = new LinkedHashMap<>();
            fileGroup.put("file", entry.getKey());
            fileGroup.put("refs", entry.getValue());
            result.add(fileGroup);
        }
        return LspJsonFormatter.toJson(result);
    }
}
