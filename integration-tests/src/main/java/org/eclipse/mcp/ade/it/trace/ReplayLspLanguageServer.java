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
package org.eclipse.mcp.ade.it.trace;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Mock LSP language server that replays responses from recorded {@link LspTraceData}.
 * <p>
 * Each LSP method delegates to the generic {@link #replay(String, Type)} helper,
 * which retrieves the next recorded response JSON for the method and deserializes it
 * using the LSP4J-aware Gson instance.
 */
public class ReplayLspLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService {

    private final LspTraceData traceData;
    private final Gson gson;
    private LanguageClient client;

    /**
     * Create a new replay LSP language server.
     *
     * @param traceData the parsed LSP trace data containing recorded responses
     */
    public ReplayLspLanguageServer(LspTraceData traceData) {
        this.traceData = traceData;
        this.gson = ReplayLspServerFactory.getLsp4jGson();
    }

    /**
     * Connect the language client proxy (called after launcher creation).
     *
     * @param client the language client proxy provided by the LSP4J launcher
     */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    // ---- Generic replay helper ----

    /**
     * Replay the next recorded response for the given LSP method.
     *
     * @param method the LSP method name (e.g., "textDocument/hover")
     * @param type   the expected result type for Gson deserialization
     * @param <T>    the result type
     * @return a completed future with the deserialized response, or null if no response recorded
     */
    private <T> CompletableFuture<T> replay(String method, Type type) {
        String json = traceData.getNextResponse(method);
        if (json == null || json.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        T result = gson.fromJson(json, type);
        return CompletableFuture.completedFuture(result);
    }

    // ---- LanguageServer ----

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        return replay("initialize", InitializeResult.class);
    }

    @Override
    public void initialized(InitializedParams params) {
        // no-op
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // no-op
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    // ---- TextDocumentService ----

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        // no-op
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        // no-op
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        // no-op
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // no-op
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return replay("textDocument/hover", Hover.class);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return replay("textDocument/references", new TypeToken<List<Location>>() {
        }.getType());
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        return replay("textDocument/definition",
                new TypeToken<Either<List<Location>, List<LocationLink>>>() {
                }.getType());
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> declaration(
            DeclarationParams params) {
        return replay("textDocument/declaration",
                new TypeToken<Either<List<Location>, List<LocationLink>>>() {
                }.getType());
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(
            TypeDefinitionParams params) {
        return replay("textDocument/typeDefinition",
                new TypeToken<Either<List<Location>, List<LocationLink>>>() {
                }.getType());
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(
            ImplementationParams params) {
        return replay("textDocument/implementation",
                new TypeToken<Either<List<Location>, List<LocationLink>>>() {
                }.getType());
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
            DocumentSymbolParams params) {
        return replay("textDocument/documentSymbol",
                new TypeToken<List<Either<SymbolInformation, DocumentSymbol>>>() {
                }.getType());
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        return replay("textDocument/completion",
                new TypeToken<Either<List<CompletionItem>, CompletionList>>() {
                }.getType());
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        return replay("textDocument/codeAction",
                new TypeToken<List<Either<Command, CodeAction>>>() {
                }.getType());
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        return replay("textDocument/formatting",
                new TypeToken<List<TextEdit>>() {
                }.getType());
    }

    @Override
    public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
        return replay("textDocument/rename", WorkspaceEdit.class);
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
        return replay("textDocument/signatureHelp", SignatureHelp.class);
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
        return replay("textDocument/codeLens",
                new TypeToken<List<CodeLens>>() {
                }.getType());
    }

    @Override
    public CompletableFuture<List<InlayHint>> inlayHint(InlayHintParams params) {
        return replay("textDocument/inlayHint",
                new TypeToken<List<InlayHint>>() {
                }.getType());
    }

    // ---- WorkspaceService ----

    @SuppressWarnings("deprecation")
    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(
            WorkspaceSymbolParams params) {
        return replay("workspace/symbol",
                new TypeToken<Either<List<SymbolInformation>, List<WorkspaceSymbol>>>() {
                }.getType());
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // no-op
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // no-op
    }
}
