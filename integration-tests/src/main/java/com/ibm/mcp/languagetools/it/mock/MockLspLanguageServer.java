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
package com.ibm.mcp.languagetools.it.mock;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * In-process mock LSP language server for integration testing.
 * <p>
 * Provides predefined responses for workspace/symbol, textDocument/documentSymbol,
 * textDocument/references, textDocument/hover, textDocument/definition,
 * textDocument/declaration, textDocument/typeDefinition, textDocument/implementation,
 * and textDocument/completion.
 */
public class MockLspLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService {

    private LanguageClient client;

    /**
     * Connect the language client (called after launcher creation).
     */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    // ---- LanguageServer ----

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setWorkspaceSymbolProvider(true);
        capabilities.setReferencesProvider(true);
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setCompletionProvider(new CompletionOptions());
        capabilities.setDeclarationProvider(true);
        capabilities.setTypeDefinitionProvider(true);
        capabilities.setImplementationProvider(true);

        InitializeResult result = new InitializeResult(capabilities);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void initialized(InitializedParams params) {
        // No-op
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // No-op
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    // ---- WorkspaceService ----

    @SuppressWarnings("deprecation")
    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        String query = params.getQuery() != null ? params.getQuery().toLowerCase() : "";
        List<SymbolInformation> allSymbols = getPredefinedWorkspaceSymbols();

        List<SymbolInformation> filtered;
        if (query.isEmpty()) {
            filtered = allSymbols;
        } else {
            filtered = new ArrayList<>();
            for (SymbolInformation sym : allSymbols) {
                if (sym.getName().toLowerCase().contains(query)) {
                    filtered.add(sym);
                }
            }
        }
        return CompletableFuture.completedFuture(Either.forLeft(filtered));
    }

    private List<SymbolInformation> getPredefinedWorkspaceSymbols() {
        // Use a placeholder URI; the actual URI is not important for workspace/symbol
        // because the integration test workspace resolves the file via cwd.
        // We use a relative-looking URI that the test workspace will match.
        String fileUri = "file:///test-workspace/test.txt";

        List<SymbolInformation> symbols = new ArrayList<>();

        symbols.add(createSymbolInformation("Greeter", SymbolKind.Class, fileUri, 0, 0, 6, 0, null));
        symbols.add(createSymbolInformation("name", SymbolKind.Field, fileUri, 1, 4, 1, 30, "Greeter"));
        symbols.add(createSymbolInformation("greet", SymbolKind.Method, fileUri, 2, 4, 5, 4, "Greeter"));
        symbols.add(createSymbolInformation("main", SymbolKind.Method, fileUri, 8, 4, 11, 4, "App"));

        return symbols;
    }

    @SuppressWarnings("deprecation")
    private SymbolInformation createSymbolInformation(String name, SymbolKind kind,
                                                       String uri, int startLine, int startChar,
                                                       int endLine, int endChar,
                                                       String containerName) {
        SymbolInformation info = new SymbolInformation();
        info.setName(name);
        info.setKind(kind);
        info.setLocation(new Location(uri, new Range(
                new Position(startLine, startChar),
                new Position(endLine, endChar))));
        info.setContainerName(containerName);
        return info;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // No-op
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // No-op
    }

    // ---- TextDocumentService ----

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        // No-op
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        // No-op
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        // No-op
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // No-op
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        String uri = params.getTextDocument().getUri();
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();

        // Return hierarchical DocumentSymbol structure
        DocumentSymbol greeterClass = new DocumentSymbol();
        greeterClass.setName("Greeter");
        greeterClass.setKind(SymbolKind.Class);
        greeterClass.setRange(new Range(new Position(0, 0), new Position(6, 0)));
        greeterClass.setSelectionRange(new Range(new Position(0, 6), new Position(0, 13)));

        DocumentSymbol nameField = new DocumentSymbol();
        nameField.setName("name");
        nameField.setKind(SymbolKind.Field);
        nameField.setRange(new Range(new Position(1, 4), new Position(1, 30)));
        nameField.setSelectionRange(new Range(new Position(1, 11), new Position(1, 15)));

        DocumentSymbol greetMethod = new DocumentSymbol();
        greetMethod.setName("greet");
        greetMethod.setKind(SymbolKind.Method);
        greetMethod.setRange(new Range(new Position(2, 4), new Position(5, 4)));
        greetMethod.setSelectionRange(new Range(new Position(2, 11), new Position(2, 16)));

        greeterClass.setChildren(List.of(nameField, greetMethod));
        result.add(Either.forRight(greeterClass));

        // Add App class if URI suggests a second file or same file with App
        DocumentSymbol appClass = new DocumentSymbol();
        appClass.setName("App");
        appClass.setKind(SymbolKind.Class);
        appClass.setRange(new Range(new Position(7, 0), new Position(12, 0)));
        appClass.setSelectionRange(new Range(new Position(7, 6), new Position(7, 9)));

        DocumentSymbol mainMethod = new DocumentSymbol();
        mainMethod.setName("main");
        mainMethod.setKind(SymbolKind.Method);
        mainMethod.setRange(new Range(new Position(8, 4), new Position(11, 4)));
        mainMethod.setSelectionRange(new Range(new Position(8, 9), new Position(8, 13)));

        appClass.setChildren(List.of(mainMethod));
        result.add(Either.forRight(appClass));

        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        String uri = params.getTextDocument().getUri();
        List<Location> locations = new ArrayList<>();
        // Return a few fixed reference locations
        locations.add(new Location(uri, new Range(new Position(9, 8), new Position(9, 20))));
        locations.add(new Location(uri, new Range(new Position(10, 8), new Position(10, 15))));
        return CompletableFuture.completedFuture(locations);
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String content = "Mock hover info for symbol at line " + params.getPosition().getLine();
        MarkupContent markup = new MarkupContent();
        markup.setKind("markdown");
        markup.setValue(content);
        Hover hover = new Hover(markup);
        return CompletableFuture.completedFuture(hover);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        Location location = new Location(uri, new Range(new Position(0, 0), new Position(0, 10)));
        return CompletableFuture.completedFuture(Either.forLeft(List.of(location)));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> declaration(DeclarationParams params) {
        String uri = params.getTextDocument().getUri();
        Location location = new Location(uri, new Range(new Position(0, 0), new Position(0, 10)));
        return CompletableFuture.completedFuture(Either.forLeft(List.of(location)));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(TypeDefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        Location location = new Location(uri, new Range(new Position(0, 0), new Position(0, 10)));
        return CompletableFuture.completedFuture(Either.forLeft(List.of(location)));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
        String uri = params.getTextDocument().getUri();
        Location location = new Location(uri, new Range(new Position(0, 0), new Position(0, 10)));
        return CompletableFuture.completedFuture(Either.forLeft(List.of(location)));
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        List<CompletionItem> items = new ArrayList<>();

        CompletionItem item1 = new CompletionItem("greet");
        item1.setKind(CompletionItemKind.Method);
        item1.setDetail("String greet()");
        items.add(item1);

        CompletionItem item2 = new CompletionItem("name");
        item2.setKind(CompletionItemKind.Field);
        item2.setDetail("String name");
        items.add(item2);

        return CompletableFuture.completedFuture(Either.forLeft(items));
    }
}
