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

import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.tools.params.FilePositionRequestParams;
import org.eclipse.mcp.ade.lsp.tools.params.FileUriRequestParams;
import org.eclipse.mcp.ade.lsp.tools.strategies.DocumentSymbolStrategy;
import org.eclipse.mcp.ade.lsp.tools.strategies.LspJsonFormatter;
import org.eclipse.mcp.ade.lsp.tools.strategies.ReferencesStrategy;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * MCP tools for LSP references (find all references).
 */
@ApplicationScoped
public class ReferencesTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    SymbolNameResolver symbolNameResolver;

    @Tool(
            name="find_references",
            description = "Find all references to a symbol. " +
                        "Accepts either symbolName (e.g., 'myMethod', 'MyClass.myMethod') or uri+line+character position. " +
                        "Returns all locations where the symbol is used across the workspace. " +
                        "Set includeEnclosingSymbol=true to enrich each reference with its enclosing symbol (method, class, etc.). " +
                        "Example with symbolName: find_references(cwd='/home/user/project', symbolName='myMethod') " +
                        "Example with position: find_references(cwd='/home/user/project', uri='file:///src/Main.java', line=10, character=5)" +
                        ToolArgDescriptions.OPEN_DOCUMENT_HINT)
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

    private CompletableFuture<String> findReferencesWithContext(
            FilePositionRequestParams params, Cancellation cancellation, Progress progress) {

        return requestExecutor.execute(
                params,
                new ReferencesStrategy(languageRegistry),
                cancellation,
                progress
        ).thenCompose(results -> {
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

            List<CompletableFuture<Map<String, List<Either<SymbolInformation, DocumentSymbol>>>>> symbolFutures =
                    byFile.keySet().stream()
                            .map(fileUri -> fetchDocumentSymbols(params.getCwd(), fileUri, cancellation, progress)
                                    .thenApply(symbols -> Map.of(fileUri, symbols))
                                    .exceptionally(ex -> Map.of(fileUri, List.of())))
                            .toList();

            return CompletableFuture.allOf(symbolFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        Map<String, List<Either<SymbolInformation, DocumentSymbol>>> allSymbols = new HashMap<>();
                        for (var future : symbolFutures) {
                            allSymbols.putAll(future.join());
                        }
                        return formatEnrichedReferences(references, allSymbols, params.getCwd());
                    });
        });
    }

    private CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> fetchDocumentSymbols(
            String cwd, String fileUri, Cancellation cancellation, Progress progress) {
        FileUriRequestParams params = new FileUriRequestParams(cwd, fileUri);
        return requestExecutor.execute(
                params,
                new DocumentSymbolStrategy(languageRegistry),
                cancellation,
                progress
        ).thenApply(results -> results.stream()
                .flatMap(List::stream)
                .toList());
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
