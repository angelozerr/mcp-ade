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
import org.eclipse.mcp.ade.lsp.tools.params.FileUriRequestParams;
import org.eclipse.mcp.ade.lsp.tools.strategies.DocumentSymbolStrategy;
import org.eclipse.mcp.ade.lsp.tools.strategies.LspJsonFormatter;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import org.eclipse.mcp.ade.tools.ToolException;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for editing code by symbol name path.
 * <p>
 * Inspired by Serena's symbol-based editing approach: symbols are identified by
 * a name path (e.g., "MyClass/myMethod") rather than line numbers, making edits
 * robust against line shifts from prior modifications.
 * <p>
 * Uses LSP {@code textDocument/documentSymbol} via {@link LspRequestExecutor#execute}
 * to resolve symbols, then applies text edits via {@link TextEditApplier}.
 */
@ApplicationScoped
public class SymbolEditTools {

    private static final String NAME_PATH_DESC =
            "Symbol name path using '/' for hierarchy (e.g., 'MyClass/myMethod', 'catalog/book[0]')";

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(name = "insert_before_symbol",
            description = "Insert code before a symbol identified by name path. " +
                    "The name path uses '/' to navigate the symbol hierarchy (e.g., 'MyClass/myMethod'). " +
                    "Use [index] to disambiguate when multiple symbols share the same name (e.g., 'MyClass/method[1]'). " +
                    "Use get_document_symbols first to discover available symbols. " +
                    "Returns the text edits to apply, or applies them directly when apply=true. " +
                    "Example: insert_before_symbol(cwd='/project', uri='file:///project/src/Main.java', " +
                    "namePath='MyClass/myMethod', body='    private int newField;\\n')")
    public CompletableFuture<String> insertBeforeSymbol(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = NAME_PATH_DESC) String namePath,
            @ToolArg(description = "Code to insert before the symbol") String body,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {
        boolean doApply = apply != null && apply;
        return fetchDocumentSymbols(cwd, uri, cancellation, progress)
                .thenApply(symbols -> {
                    DocumentSymbol symbol = SymbolNamePathResolver.resolve(symbols, namePath);
                    int insertLine = symbol.getRange().getStart().getLine();

                    TextEdit edit = new TextEdit(
                            new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                            ensureTrailingNewline(body));
                    List<TextEdit> edits = List.of(edit);
                    if (doApply) {
                        TextEditApplier.applyTextEdits(uri, edits);
                    }
                    return LspJsonFormatter.toJson(LspJsonFormatter.textEditsResult(edits, doApply));
                });
    }

    @Tool(name = "insert_after_symbol",
            description = "Insert code after a symbol identified by name path. " +
                    "The name path uses '/' to navigate the symbol hierarchy (e.g., 'MyClass/myMethod'). " +
                    "Use [index] to disambiguate when multiple symbols share the same name (e.g., 'MyClass/method[1]'). " +
                    "Use get_document_symbols first to discover available symbols. " +
                    "Returns the text edits to apply, or applies them directly when apply=true. " +
                    "Example: insert_after_symbol(cwd='/project', uri='file:///project/src/Main.java', " +
                    "namePath='MyClass/myMethod', body='    public void newMethod() {}\\n')")
    public CompletableFuture<String> insertAfterSymbol(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = NAME_PATH_DESC) String namePath,
            @ToolArg(description = "Code to insert after the symbol") String body,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {
        boolean doApply = apply != null && apply;
        return fetchDocumentSymbols(cwd, uri, cancellation, progress)
                .thenApply(symbols -> {
                    DocumentSymbol symbol = SymbolNamePathResolver.resolve(symbols, namePath);
                    int insertLine = symbol.getRange().getEnd().getLine() + 1;

                    TextEdit edit = new TextEdit(
                            new Range(new Position(insertLine, 0), new Position(insertLine, 0)),
                            ensureTrailingNewline(body));
                    List<TextEdit> edits = List.of(edit);
                    if (doApply) {
                        TextEditApplier.applyTextEdits(uri, edits);
                    }
                    return LspJsonFormatter.toJson(LspJsonFormatter.textEditsResult(edits, doApply));
                });
    }

    @Tool(name = "replace_symbol_body",
            description = "Replace the entire body of a symbol identified by name path. " +
                    "The name path uses '/' to navigate the symbol hierarchy (e.g., 'MyClass/myMethod'). " +
                    "Use [index] to disambiguate when multiple symbols share the same name (e.g., 'MyClass/method[1]'). " +
                    "Use get_document_symbols first to see available symbols and their ranges. " +
                    "Returns the text edits to apply, or applies them directly when apply=true. " +
                    "Example: replace_symbol_body(cwd='/project', uri='file:///project/src/Main.java', " +
                    "namePath='MyClass/myMethod', body='    public void myMethod() {\\n        // new impl\\n    }')")
    public CompletableFuture<String> replaceSymbolBody(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = NAME_PATH_DESC) String namePath,
            @ToolArg(description = "New code to replace the symbol's entire body") String body,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {
        boolean doApply = apply != null && apply;
        return fetchDocumentSymbols(cwd, uri, cancellation, progress)
                .thenApply(symbols -> {
                    DocumentSymbol symbol = SymbolNamePathResolver.resolve(symbols, namePath);
                    Range range = symbol.getRange();

                    TextEdit edit = new TextEdit(range, body);
                    List<TextEdit> edits = List.of(edit);
                    if (doApply) {
                        TextEditApplier.applyTextEdits(uri, edits);
                    }
                    return LspJsonFormatter.toJson(LspJsonFormatter.textEditsResult(edits, doApply));
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
        ).thenApply(results -> {
            if (results.isEmpty()) {
                throw new ToolException("No document symbols found for: " + fileUri);
            }
            return results.stream()
                    .flatMap(List::stream)
                    .toList();
        });
    }

    private static String ensureTrailingNewline(String text) {
        if (!text.endsWith("\n")) {
            return text + "\n";
        }
        return text;
    }
}
