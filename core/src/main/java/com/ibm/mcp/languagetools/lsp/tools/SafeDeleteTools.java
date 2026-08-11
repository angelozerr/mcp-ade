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

import com.ibm.mcp.languagetools.language.LanguageRegistry;
import com.ibm.mcp.languagetools.lsp.tools.params.FilePositionRequestParams;
import com.ibm.mcp.languagetools.lsp.tools.params.FileUriRequestParams;
import com.ibm.mcp.languagetools.lsp.tools.strategies.DocumentSymbolStrategy;
import com.ibm.mcp.languagetools.lsp.tools.strategies.LspJsonFormatter;
import com.ibm.mcp.languagetools.lsp.tools.strategies.ReferencesStrategy;
import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
import com.ibm.mcp.languagetools.tools.ToolException;
import com.ibm.mcp.languagetools.utils.UriUtils;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP tool for safely deleting a symbol after checking for external references.
 * <p>
 * Uses LSP {@code textDocument/documentSymbol} to resolve the target symbol by name path,
 * then {@code textDocument/references} to check for external usages. If external references
 * exist, the deletion is blocked with a report of the reference locations. Otherwise,
 * the symbol's range is removed (or previewed).
 */
@ApplicationScoped
public class SafeDeleteTools {

    private static final String NAME_PATH_DESC =
            "Symbol name path using '/' for hierarchy (e.g., 'MyClass/myMethod', 'catalog/book[0]')";

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(name = "safe_delete_symbol",
            description = "Safely delete a symbol after checking for external references. " +
                    "The name path uses '/' to navigate the symbol hierarchy (e.g., 'MyClass/myMethod'). " +
                    "Use [index] to disambiguate when multiple symbols share the same name (e.g., 'MyClass/method[1]'). " +
                    "Use get_document_symbols first to discover available symbols. " +
                    "First checks for references outside the symbol's own range. " +
                    "If external references exist, reports them and blocks deletion. " +
                    "If no external references: returns a preview of the deletion, or applies it when apply=true. " +
                    "Example: safe_delete_symbol(cwd='/project', uri='file:///project/src/Main.java', " +
                    "namePath='MyClass/unusedMethod')")
    public CompletableFuture<String> safeDeleteSymbol(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = NAME_PATH_DESC) String namePath,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {
        boolean doApply = apply != null && apply;
        return fetchDocumentSymbols(cwd, uri, cancellation, progress)
                .thenCompose(symbols -> {
                    DocumentSymbol symbol = SymbolNamePathResolver.resolve(symbols, namePath);
                    Position refPosition = symbol.getSelectionRange().getStart();

                    FilePositionRequestParams refParams = new FilePositionRequestParams(
                            cwd, uri, refPosition.getLine(), refPosition.getCharacter());

                    return requestExecutor.execute(
                            refParams,
                            new ReferencesStrategy(languageRegistry),
                            cancellation,
                            progress
                    ).thenApply(results -> {
                        List<? extends Location> allReferences = results.stream()
                                .flatMap(List::stream)
                                .distinct()
                                .toList();

                        Range symbolRange = symbol.getRange();
                        List<? extends Location> externalRefs = allReferences.stream()
                                .filter(ref -> !isInsideRange(ref, symbolRange))
                                .toList();

                        if (!externalRefs.isEmpty()) {
                            String cwdUri = UriUtils.cwdToUriPrefix(cwd);
                            List<Map<String, Object>> refLocations = externalRefs.stream()
                                    .map(loc -> {
                                        Map<String, Object> entry = new LinkedHashMap<>();
                                        entry.put("uri", UriUtils.compactUri(loc.getUri(), cwdUri));
                                        Range r = loc.getRange();
                                        entry.put("range", (r.getStart().getLine() + 1) + ":" + r.getStart().getCharacter()
                                                + "-" + (r.getEnd().getLine() + 1) + ":" + r.getEnd().getCharacter());
                                        return entry;
                                    })
                                    .toList();
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("status", "blocked");
                            result.put("message", "Cannot safely delete: " + externalRefs.size() + " external reference(s) found");
                            result.put("externalReferences", refLocations);
                            return LspJsonFormatter.toJson(result);
                        }

                        TextEdit edit = new TextEdit(symbolRange, "");
                        List<TextEdit> edits = List.of(edit);
                        if (doApply) {
                            TextEditApplier.applyTextEdits(uri, edits);
                        }
                        return LspJsonFormatter.toJson(LspJsonFormatter.textEditsResult(edits, doApply));
                    });
                });
    }

    /**
     * Check whether a reference location falls inside the given symbol range.
     * A reference is considered inside if its start position is within the symbol range boundaries.
     */
    static boolean isInsideRange(Location ref, Range symbolRange) {
        Position refStart = ref.getRange().getStart();
        Position symStart = symbolRange.getStart();
        Position symEnd = symbolRange.getEnd();

        if (refStart.getLine() < symStart.getLine() || refStart.getLine() > symEnd.getLine()) return false;
        if (refStart.getLine() == symStart.getLine() && refStart.getCharacter() < symStart.getCharacter()) return false;
        if (refStart.getLine() == symEnd.getLine() && refStart.getCharacter() > symEnd.getCharacter()) return false;
        return true;
    }

    private CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> fetchDocumentSymbols(
            String cwd, String fileUri, Cancellation cancellation, Progress progress) {
        FileUriRequestParams params = new FileUriRequestParams(cwd, fileUri);
        return requestExecutor.execute(
                params,
                new DocumentSymbolStrategy(languageRegistry),
                cancellation,
                progress
        ).thenApply(results -> {
            if (results.isEmpty()) {
                throw new ToolException("No document symbols found for: " + fileUri);
            }
            return results.stream()
                    .flatMap(List::stream)
                    .toList();
        });
    }
}
