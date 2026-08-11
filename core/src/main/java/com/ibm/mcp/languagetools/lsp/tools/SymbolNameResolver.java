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
import com.ibm.mcp.languagetools.lsp.tools.params.FilePositionRequestParams;
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

    /**
     * Resolve params from either symbolName or position.
     * When symbolName is provided, resolves it via workspace/symbol.
     * Otherwise uses the provided uri+line+character.
     */
    public CompletableFuture<FilePositionRequestParams> resolveParams(
            String cwd, String symbolName, String uri, Integer line, Integer character) {
        if (symbolName != null && !symbolName.isEmpty()) {
            return resolve(cwd, symbolName);
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
     * @param symbolName symbol name or qualified path (e.g., "myMethod", "MyClass.myMethod", "MyClass/myMethod")
     * @return resolved file position, or failed future if not found
     */
    public CompletableFuture<FilePositionRequestParams> resolve(String cwd, String symbolName) {
        String query = extractQueryName(symbolName);

        return serverResolver.getLspServersForWorkspace(cwd,
                        server -> server.isEnabled() && server.supportsCapability(LspCapability.WORKSPACE_SYMBOL))
                .thenCompose(servers -> {
                    if (servers.isEmpty()) {
                        return CompletableFuture.failedFuture(
                                new IllegalStateException("No language server supports workspace symbol search"));
                    }

                    WorkspaceSymbolParams params = new WorkspaceSymbolParams(query);

                    List<CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>>> futures =
                            servers.stream()
                                    .map(server -> queryServer(server, params))
                                    .toList();

                    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> {
                                List<SymbolInformation> allSymbols = futures.stream()
                                        .map(CompletableFuture::join)
                                        .filter(Objects::nonNull)
                                        .flatMap(either -> toSymbolInformations(either).stream())
                                        .toList();

                                SymbolInformation match = findBestMatch(allSymbols, symbolName);
                                if (match == null || match.getLocation() == null) {
                                    throw new IllegalArgumentException("Symbol not found: " + symbolName);
                                }

                                Location loc = match.getLocation();
                                LOG.infof("Resolved symbol '%s' to %s:%d:%d",
                                        symbolName, loc.getUri(),
                                        loc.getRange().getStart().getLine(),
                                        loc.getRange().getStart().getCharacter());

                                return new FilePositionRequestParams(
                                        cwd,
                                        loc.getUri(),
                                        loc.getRange().getStart().getLine(),
                                        loc.getRange().getStart().getCharacter());
                            });
                });
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
        int dotIdx = symbolName.lastIndexOf('.');
        int slashIdx = symbolName.lastIndexOf('/');
        int separatorIdx = Math.max(dotIdx, slashIdx);
        return separatorIdx >= 0 ? symbolName.substring(separatorIdx + 1) : symbolName;
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
                        && containerName.equals(sym.getContainerName())
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

        // 4. First result with a location
        return symbols.stream()
                .filter(sym -> sym.getLocation() != null)
                .findFirst()
                .orElse(null);
    }

    private static String extractContainerName(String symbolName) {
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
}
