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
package org.eclipse.mcp.ade.it.mock;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.eclipse.mcp.ade.it.trace.LspTraceData;
import org.eclipse.mcp.ade.it.trace.ReplayLspServerFactory;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.*;

import java.lang.reflect.Type;
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
 * <p>
 * The predefined symbols model a simple two-class structure:
 * <ul>
 *   <li>{@code Greeter} class with fields {@code name} and method {@code greet()}</li>
 *   <li>{@code App} class with method {@code main()}</li>
 * </ul>
 */
public class MockLspLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService {

    private final Gson gson = ReplayLspServerFactory.getLsp4jGson();
    private LanguageClient client;
    private String rootUri;

    /**
     * Connect the language client (called after launcher creation).
     *
     * @param client the language client proxy provided by the LSP4J launcher
     */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    /**
     * Try to replay a response from registered trace data.
     *
     * @return the replayed result, or {@code null} if no trace data is registered
     */
    private <T> CompletableFuture<T> tryReplay(String method, Type type) {
        LspTraceData traceData = ReplayLspServerFactory.getData("mock-lsp");
        if (traceData == null) {
            return null;
        }
        // Replay mode active — never fall through to mock defaults
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
        this.rootUri = params.getRootUri();

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
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
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

    /**
     * Handle workspace/symbol requests.
     * Returns predefined symbols filtered by the query string (case-insensitive contains).
     */
    @SuppressWarnings("deprecation")
    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> symbol(WorkspaceSymbolParams params) {
        CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> replay =
                tryReplay("workspace/symbol", new TypeToken<Either<List<SymbolInformation>, List<WorkspaceSymbol>>>() {}.getType());
        if (replay != null) return replay;
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

    /**
     * Build the predefined workspace symbols list using the actual workspace root URI.
     *
     * @return list of symbol information for the test workspace
     */
    private List<SymbolInformation> getPredefinedWorkspaceSymbols() {
        String fileUri = buildFileUri("test.txt");

        List<SymbolInformation> symbols = new ArrayList<>();

        symbols.add(createSymbolInformation("Greeter", SymbolKind.Class, fileUri, 0, 0, 6, 0, null));
        symbols.add(createSymbolInformation("name", SymbolKind.Field, fileUri, 1, 4, 1, 30, "Greeter"));
        symbols.add(createSymbolInformation("greet", SymbolKind.Method, fileUri, 2, 4, 5, 4, "Greeter"));
        symbols.add(createSymbolInformation("main", SymbolKind.Method, fileUri, 8, 4, 11, 4, "App"));

        return symbols;
    }

    /**
     * Build a file URI relative to the workspace root.
     *
     * @param filename the filename relative to the workspace root
     * @return the full file URI
     */
    private String buildFileUri(String filename) {
        if (rootUri != null) {
            String base = rootUri.endsWith("/") ? rootUri : rootUri + "/";
            return base + filename;
        }
        return "file:///test-workspace/" + filename;
    }

    /**
     * Create a {@link SymbolInformation} with the given properties.
     *
     * @param name          the symbol name
     * @param kind          the symbol kind
     * @param uri           the file URI containing the symbol
     * @param startLine     the start line (0-based)
     * @param startChar     the start character (0-based)
     * @param endLine       the end line (0-based)
     * @param endChar       the end character (0-based)
     * @param containerName the container name, or {@code null}
     * @return the symbol information
     */
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
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
    }

    // ---- TextDocumentService ----

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
    }

    /**
     * Handle textDocument/documentSymbol requests.
     * Returns a hierarchical {@link DocumentSymbol} tree matching the test workspace content.
     */
    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
        CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> replay =
                tryReplay("textDocument/documentSymbol", new TypeToken<List<Either<SymbolInformation, DocumentSymbol>>>() {}.getType());
        if (replay != null) return replay;

        String uri = params.getTextDocument().getUri();
        if (uri != null && uri.endsWith(".xml")) {
            return CompletableFuture.completedFuture(buildXmlDocumentSymbols());
        }
        return CompletableFuture.completedFuture(buildDefaultDocumentSymbols());
    }

    private List<Either<SymbolInformation, DocumentSymbol>> buildXmlDocumentSymbols() {
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();

        DocumentSymbol catalog = new DocumentSymbol();
        catalog.setName("catalog");
        catalog.setKind(SymbolKind.Field);
        catalog.setRange(new Range(new Position(1, 0), new Position(10, 10)));
        catalog.setSelectionRange(new Range(new Position(1, 1), new Position(1, 8)));

        DocumentSymbol book1 = new DocumentSymbol();
        book1.setName("book");
        book1.setKind(SymbolKind.Field);
        book1.setRange(new Range(new Position(2, 2), new Position(5, 9)));
        book1.setSelectionRange(new Range(new Position(2, 3), new Position(2, 7)));

        DocumentSymbol title1 = new DocumentSymbol();
        title1.setName("title");
        title1.setKind(SymbolKind.Field);
        title1.setRange(new Range(new Position(3, 4), new Position(3, 35)));
        title1.setSelectionRange(new Range(new Position(3, 5), new Position(3, 10)));

        DocumentSymbol author1 = new DocumentSymbol();
        author1.setName("author");
        author1.setKind(SymbolKind.Field);
        author1.setRange(new Range(new Position(4, 4), new Position(4, 29)));
        author1.setSelectionRange(new Range(new Position(4, 5), new Position(4, 11)));

        book1.setChildren(List.of(title1, author1));

        DocumentSymbol book2 = new DocumentSymbol();
        book2.setName("book");
        book2.setKind(SymbolKind.Field);
        book2.setRange(new Range(new Position(6, 2), new Position(9, 9)));
        book2.setSelectionRange(new Range(new Position(6, 3), new Position(6, 7)));

        DocumentSymbol title2 = new DocumentSymbol();
        title2.setName("title");
        title2.setKind(SymbolKind.Field);
        title2.setRange(new Range(new Position(7, 4), new Position(7, 29)));
        title2.setSelectionRange(new Range(new Position(7, 5), new Position(7, 10)));

        DocumentSymbol author2 = new DocumentSymbol();
        author2.setName("author");
        author2.setKind(SymbolKind.Field);
        author2.setRange(new Range(new Position(8, 4), new Position(8, 31)));
        author2.setSelectionRange(new Range(new Position(8, 5), new Position(8, 11)));

        book2.setChildren(List.of(title2, author2));

        catalog.setChildren(List.of(book1, book2));
        result.add(Either.forRight(catalog));

        return result;
    }

    private List<Either<SymbolInformation, DocumentSymbol>> buildDefaultDocumentSymbols() {
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();

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

        return result;
    }

    /**
     * Handle textDocument/references requests.
     * Returns fixed reference locations within the requested document.
     */
    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        CompletableFuture<List<? extends Location>> replay =
                tryReplay("textDocument/references", new TypeToken<List<Location>>() {}.getType());
        if (replay != null) return replay;
        String uri = params.getTextDocument().getUri();
        List<Location> locations = new ArrayList<>();
        locations.add(new Location(uri, new Range(new Position(9, 8), new Position(9, 20))));
        locations.add(new Location(uri, new Range(new Position(10, 8), new Position(10, 15))));
        return CompletableFuture.completedFuture(locations);
    }

    /**
     * Handle textDocument/hover requests.
     * Returns a simple markdown hover with the symbol position.
     */
    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        CompletableFuture<Hover> replay = tryReplay("textDocument/hover", Hover.class);
        if (replay != null) return replay;
        String content = "Mock hover info for symbol at line " + params.getPosition().getLine();
        MarkupContent markup = new MarkupContent();
        markup.setKind("markdown");
        markup.setValue(content);
        Hover hover = new Hover(markup);
        return CompletableFuture.completedFuture(hover);
    }

    /**
     * Handle textDocument/definition requests.
     * Returns a fixed definition location in the requested document.
     */
    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> replay = tryReplay("textDocument/definition", ReplayLspServerFactory.LOCATION_EITHER_TYPE);
        if (replay != null) return replay;
        String uri = params.getTextDocument().getUri();
        Location location = new Location(uri, new Range(new Position(0, 0), new Position(0, 10)));
        return CompletableFuture.completedFuture(Either.forLeft(List.of(location)));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> declaration(DeclarationParams params) {
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> replay = tryReplay("textDocument/declaration", ReplayLspServerFactory.LOCATION_EITHER_TYPE);
        if (replay != null) return replay;
        String uri = params.getTextDocument().getUri();
        Location location = new Location(uri, new Range(new Position(0, 0), new Position(0, 10)));
        return CompletableFuture.completedFuture(Either.forLeft(List.of(location)));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> typeDefinition(TypeDefinitionParams params) {
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> replay = tryReplay("textDocument/typeDefinition", ReplayLspServerFactory.LOCATION_EITHER_TYPE);
        if (replay != null) return replay;
        String uri = params.getTextDocument().getUri();
        Location location = new Location(uri, new Range(new Position(0, 0), new Position(0, 10)));
        return CompletableFuture.completedFuture(Either.forLeft(List.of(location)));
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation(ImplementationParams params) {
        CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> replay = tryReplay("textDocument/implementation", ReplayLspServerFactory.LOCATION_EITHER_TYPE);
        if (replay != null) return replay;
        String uri = params.getTextDocument().getUri();
        Location location = new Location(uri, new Range(new Position(0, 0), new Position(0, 10)));
        return CompletableFuture.completedFuture(Either.forLeft(List.of(location)));
    }

    /**
     * Handle textDocument/completion requests.
     * Returns a fixed set of completion items for testing.
     */
    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> replay =
                tryReplay("textDocument/completion", new TypeToken<Either<List<CompletionItem>, CompletionList>>() {}.getType());
        if (replay != null) return replay;
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
