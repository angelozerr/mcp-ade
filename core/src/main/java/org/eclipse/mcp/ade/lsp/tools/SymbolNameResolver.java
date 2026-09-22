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
import org.eclipse.mcp.ade.lsp.client.LspCapability;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerResolver;
import org.eclipse.mcp.ade.lsp.tools.params.FilePositionRequestParams;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves a symbol name to a file position by querying workspace/symbol on all running servers.
 * Enables symbol-name-based navigation as an alternative to position-based (line/character).
 */
@ApplicationScoped
public class SymbolNameResolver {

    private static final Logger LOG = Logger.getLogger(SymbolNameResolver.class);

    @Inject
    LspServerResolver serverResolver;

    @Inject
    LanguageRegistry languageRegistry;

    /**
     * Resolve params from either symbolName or position.
     * When symbolName is provided, resolves it via workspace/symbol.
     * Otherwise uses the provided uri+line+character.
     */
    public CompletableFuture<FilePositionRequestParams> resolveParams(
            String cwd, String symbolName, String uri, Integer line, Integer character,
            String fileExt) {
        if (symbolName != null && !symbolName.isEmpty()) {
            return resolve(cwd, symbolName, fileExt);
        }
        if (uri == null || line == null || character == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Either symbolName or uri+line+character must be provided"));
        }
        return CompletableFuture.completedFuture(new FilePositionRequestParams(cwd, uri, line, character));
    }

    /**
     * Resolve a symbol name to a file position.
     *
     * @param cwd        workspace root path
     * @param symbolName symbol name or qualified path (e.g., "getChildren", "DOMNode.getChildren", "DOMNode.getChildren()")
     * @param fileExt    optional file extension hint (e.g., ".java") to filter servers
     * @return resolved file position, or failed future if not found
     */
    public CompletableFuture<FilePositionRequestParams> resolve(String cwd, String symbolName, String fileExt) {
        String queryName = extractQueryName(symbolName);
        String containerName = extractContainerName(symbolName);
        String languageId = resolveLanguageId(fileExt);

        return serverResolver.getLspServersForWorkspace(cwd,
                        server -> server.isEnabled()
                                && server.supportsCapability(LspCapability.WORKSPACE_SYMBOL)
                                && matchesLanguage(server, languageId))
                .thenCompose(servers -> {
                    if (servers.isEmpty()) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("No language server supports workspace symbol search"));
                    }

                    return queryWorkspaceSymbols(servers, queryName)
                            .thenCompose(allSymbols -> {
                                SymbolInformation match = findBestMatch(allSymbols, symbolName);
                                if (match != null && match.getLocation() != null) {
                                    return CompletableFuture.completedFuture(toResolvedParams(cwd, symbolName, match));
                                }
                                // Fallback: some LS (e.g. JDT.LS) only support type names in workspace/symbol.
                                // Retry with the container name, then resolve the member via documentSymbol.
                                if (containerName != null) {
                                    return queryWorkspaceSymbols(servers, containerName)
                                            .thenCompose(containerSymbols -> {
                                                SymbolInformation containerMatch = findBestMatch(containerSymbols, containerName);
                                                if (containerMatch == null || containerMatch.getLocation() == null) {
                                                    return CompletableFuture.failedFuture(
                                                            new IllegalArgumentException("Symbol not found: " + symbolName));
                                                }
                                                return resolveMethodInFile(cwd, symbolName, queryName, containerMatch, servers);
                                            });
                                }
                                return CompletableFuture.failedFuture(
                                        new IllegalArgumentException("Symbol not found: " + symbolName));
                            });
                });
    }

    private CompletableFuture<List<SymbolInformation>> queryWorkspaceSymbols(
            List<LspServer> servers, String query) {
        WorkspaceSymbolParams params = new WorkspaceSymbolParams(query);
        List<CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>> futures =
                servers.stream()
                        .map(server -> queryServer(server, params))
                        .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> futures.stream()
                        .map(CompletableFuture::join)
                        .filter(Objects::nonNull)
                        .flatMap(either -> toSymbolInformations(either).stream())
                        .toList());
    }

    private CompletableFuture<FilePositionRequestParams> resolveMethodInFile(
            String cwd, String symbolName, String memberName, SymbolInformation containerMatch,
            List<LspServer> servers) {
        String fileUri = containerMatch.getLocation().getUri();
        TextDocumentIdentifier docId = new TextDocumentIdentifier(fileUri);
        DocumentSymbolParams docParams = new DocumentSymbolParams(docId);

        List<CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>> docFutures =
                servers.stream()
                        .filter(s -> s.supportsCapability(LspCapability.DOCUMENT_SYMBOL))
                        .map(server -> server.getLanguageServer()
                                .getTextDocumentService()
                                .documentSymbol(docParams)
                                .exceptionally(ex -> {
                                    LOG.debugf("documentSymbol failed on %s: %s", server.getConfig().getServerId(), ex.getMessage());
                                    return null;
                                }))
                        .toList();

        return CompletableFuture.allOf(docFutures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    for (var future : docFutures) {
                        List<Either<SymbolInformation, DocumentSymbol>> result = future.join();
                        if (result == null) continue;
                        Position pos = findMemberPosition(result, memberName);
                        if (pos != null) {
                            LOG.infof("Resolved symbol '%s' via documentSymbol to %s:%d:%d",
                                    symbolName, fileUri, pos.getLine(), pos.getCharacter());
                            return new FilePositionRequestParams(cwd, fileUri, pos.getLine(), pos.getCharacter());
                        }
                    }
                    // Member not found in documentSymbol — fall back to class position
                    return toResolvedParams(cwd, symbolName, containerMatch);
                });
    }

    private static Position findMemberPosition(List<Either<SymbolInformation, DocumentSymbol>> symbols, String memberName) {
        for (var either : symbols) {
            if (either.isRight()) {
                Position pos = findInDocumentSymbol(either.getRight(), memberName);
                if (pos != null) return pos;
            } else if (either.isLeft()) {
                SymbolInformation si = either.getLeft();
                if (memberName.equals(si.getName()) && si.getLocation() != null) {
                    return si.getLocation().getRange().getStart();
                }
            }
        }
        return null;
    }

    private static Position findInDocumentSymbol(DocumentSymbol symbol, String memberName) {
        if (memberName.equals(symbol.getName())) {
            return symbol.getSelectionRange().getStart();
        }
        if (symbol.getChildren() != null) {
            for (DocumentSymbol child : symbol.getChildren()) {
                Position pos = findInDocumentSymbol(child, memberName);
                if (pos != null) return pos;
            }
        }
        return null;
    }

    private static FilePositionRequestParams toResolvedParams(String cwd, String symbolName, SymbolInformation match) {
        Location loc = match.getLocation();
        LOG.infof("Resolved symbol '%s' to %s:%d:%d",
                symbolName, loc.getUri(),
                loc.getRange().getStart().getLine(),
                loc.getRange().getStart().getCharacter());
        return new FilePositionRequestParams(
                cwd, loc.getUri(),
                loc.getRange().getStart().getLine(),
                loc.getRange().getStart().getCharacter());
    }

    private CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> queryServer(
            LspServer server, WorkspaceSymbolParams params) {
        return server.getLanguageServer()
                .getWorkspaceService()
                .symbol(params)
                .exceptionally(ex -> {
                    LOG.debugf("workspace/symbol failed on %s: %s", server.getConfig().getServerId(), ex.getMessage());
                    return null;
                });
    }

    private static String extractQueryName(String symbolName) {
        symbolName = stripParentheses(symbolName);
        int dotIdx = symbolName.lastIndexOf('.');
        int slashIdx = symbolName.lastIndexOf('/');
        int separatorIdx = Math.max(dotIdx, slashIdx);
        return separatorIdx >= 0 ? symbolName.substring(separatorIdx + 1) : symbolName;
    }

    // AI models often pass "DOMNode.getChildren()" with parentheses — strip them before matching.
    private static String stripParentheses(String name) {
        int parenIdx = name.indexOf('(');
        return parenIdx >= 0 ? name.substring(0, parenIdx) : name;
    }

    static SymbolInformation findBestMatch(List<SymbolInformation> symbols, String symbolName) {
        if (symbols.isEmpty()) {
            return null;
        }

        String simpleName = extractQueryName(symbolName);
        String containerName = extractContainerName(symbolName);

        // 1. Exact match on name + container
        if (containerName != null) {
            for (SymbolInformation sym : symbols) {
                if (simpleName.equals(sym.getName())
                        && matchesContainerName(containerName, sym.getContainerName())
                        && sym.getLocation() != null) {
                    return sym;
                }
            }
        }

        // 2. Exact match on name only
        for (SymbolInformation sym : symbols) {
            if (simpleName.equals(sym.getName()) && sym.getLocation() != null) {
                return sym;
            }
        }

        // 3. Case-insensitive match
        for (SymbolInformation sym : symbols) {
            if (simpleName.equalsIgnoreCase(sym.getName()) && sym.getLocation() != null) {
                return sym;
            }
        }

        return null;
    }

    // LSP returns fully qualified names (e.g. "org.eclipse.lemminx.dom.DOMNode") but AI passes simple names ("DOMNode").
    private static boolean matchesContainerName(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        if (expected.equals(actual)) {
            return true;
        }
        return actual.endsWith("." + expected) || actual.endsWith("/" + expected);
    }

    private static String extractContainerName(String symbolName) {
        symbolName = stripParentheses(symbolName);
        int dotIdx = symbolName.lastIndexOf('.');
        int slashIdx = symbolName.lastIndexOf('/');
        int separatorIdx = Math.max(dotIdx, slashIdx);
        return separatorIdx >= 0 ? symbolName.substring(0, separatorIdx) : null;
    }

    private static List<SymbolInformation> toSymbolInformations(
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> either) {
        if (either.isLeft()) {
            return either.getLeft().stream()
                    .map(si -> (SymbolInformation) si)
                    .toList();
        }
        return either.getRight().stream()
                .map(ws -> {
                    SymbolInformation info = new SymbolInformation();
                    info.setName(ws.getName());
                    info.setKind(ws.getKind());
                    info.setContainerName(ws.getContainerName());
                    Either<Location, WorkspaceSymbolLocation> location = ws.getLocation();
                    if (location != null && location.isLeft()) {
                        info.setLocation(location.getLeft());
                    }
                    return info;
                })
                .filter(info -> info.getLocation() != null)
                .toList();
    }

    private String resolveLanguageId(String fileExt) {
        if (fileExt == null || fileExt.isEmpty()) {
            return null;
        }
        String ext = fileExt.startsWith(".") ? fileExt : "." + fileExt;
        return languageRegistry.detectLanguage(java.net.URI.create("file:///dummy" + ext)).orElse(null);
    }

    private static boolean matchesLanguage(LspServer server, String languageId) {
        if (languageId == null) {
            return true;
        }
        var selector = server.getConfig().getDocumentSelector();
        if (selector == null) {
            return true;
        }
        return selector.getLanguages().contains(languageId);
    }
}
