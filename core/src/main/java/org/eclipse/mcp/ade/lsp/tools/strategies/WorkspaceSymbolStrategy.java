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
package org.eclipse.mcp.ade.lsp.tools.strategies;

import org.eclipse.mcp.ade.lsp.client.LspCapability;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerResolver;
import org.eclipse.mcp.ade.lsp.tools.LspRequestExecutor;
import org.eclipse.mcp.ade.lsp.tools.params.WorkspaceSymbolRequestParams;
import org.eclipse.mcp.ade.operation.OperationContext;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Strategy for LSP workspace/symbol requests.
 */
public class WorkspaceSymbolStrategy implements LspRequestExecutor.LspRequestStrategy<WorkspaceSymbolRequestParams, WorkspaceSymbolParams, Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> {

    @Override
    public LspCapability getCapability() {
        return LspCapability.WORKSPACE_SYMBOL;
    }

    @Override
    public String getTitle() {
        return "Workspace symbols";
    }

    @Override
    public CompletableFuture<List<LspServer>> resolveServers(
            LspServerResolver resolver,
            WorkspaceSymbolRequestParams params, ProgressMonitor progressMonitor,
            OperationContext operationContext) {

        return resolver.getLspServersForWorkspace(params.getCwd(),
                server -> server.isEnabled() && server.supportsCapability(getCapability()));
    }

    @Override
    public WorkspaceSymbolParams buildLspParams(WorkspaceSymbolRequestParams params) {
        WorkspaceSymbolParams lspParams = new WorkspaceSymbolParams();
        lspParams.setQuery(params.getQuery());
        return lspParams;
    }

    @Override
    public CompletableFuture<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> executeRequest(
            LspServer server, WorkspaceSymbolParams lspParams) {
        return server.getLanguageServer()
                .getWorkspaceService()
                .symbol(lspParams);
    }

    @Override
    public Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> getEmptyResult() {
        return Either.forLeft(Collections.emptyList());
    }

    @Override
    public boolean isValidResult(Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> result) {
        if (result == null) return false;
        if (result.isLeft()) return !result.getLeft().isEmpty();
        if (result.isRight()) return !result.getRight().isEmpty();
        return false;
    }

    @Override
    public String formatResults(WorkspaceSymbolRequestParams params, List<Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>>> results) {
        // Merge all symbols from all results
        List<SymbolInformation> allSymbols = results.stream()
                .flatMap(either -> {
                    if (either.isLeft()) {
                        return either.getLeft().stream();
                    } else {
                        // Convert WorkspaceSymbol to SymbolInformation
                        return either.getRight().stream()
                                .map(ws -> {
                                    SymbolInformation info = new SymbolInformation();
                                    info.setName(ws.getName());
                                    info.setKind(ws.getKind());
                                    info.setContainerName(ws.getContainerName());

                                    // Extract location from WorkspaceSymbol.location (Either<Location, LocationData>)
                                    Either<Location, ?> location = ws.getLocation();
                                    if (location != null && location.isLeft()) {
                                        info.setLocation(location.getLeft());
                                    }

                                    return info;
                                });
                    }
                })
                .distinct()
                .toList();

        // Apply post-query filtering
        allSymbols = filterSymbols(allSymbols, params.getKind(), params.getPathPattern(),
                params.getContainerName(), params.getMaxResults());

        if (allSymbols.isEmpty()) {
            return formatNoResultFound(params);
        }

        String cwdUri = LspJsonFormatter.cwdToUriPrefix(params.getCwd());
        return LspJsonFormatter.toJson(allSymbols.stream().map(sym -> LspJsonFormatter.symbolInfo(sym, cwdUri)).toList());
    }

    /**
     * Filters a list of symbols by kind, path pattern, container name, and max results.
     * Package-visible for unit testing.
     */
    static List<SymbolInformation> filterSymbols(List<SymbolInformation> symbols,
                                                  String kind,
                                                  String pathPattern,
                                                  String containerName,
                                                  Integer maxResults) {
        Stream<SymbolInformation> stream = symbols.stream();

        // Filter by symbol kind
        if (kind != null && !kind.isBlank()) {
            stream = stream.filter(sym -> sym.getKind() != null
                    && sym.getKind().name().equalsIgnoreCase(kind));
        }

        // Filter by path pattern (glob)
        if (pathPattern != null && !pathPattern.isBlank()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pathPattern);
            stream = stream.filter(sym -> {
                if (sym.getLocation() == null || sym.getLocation().getUri() == null) {
                    return false;
                }
                try {
                    String uriStr = sym.getLocation().getUri();
                    URI uri = URI.create(uriStr);
                    String path = uri.getPath();
                    if (path == null) {
                        return false;
                    }
                    return matcher.matches(Path.of(path).getFileName())
                            || matcher.matches(Path.of(path));
                } catch (Exception e) {
                    return false;
                }
            });
        }

        // Filter by container name
        if (containerName != null && !containerName.isBlank()) {
            stream = stream.filter(sym -> sym.getContainerName() != null
                    && sym.getContainerName().toLowerCase().contains(containerName.toLowerCase()));
        }

        // Limit results
        if (maxResults != null && maxResults > 0) {
            stream = stream.limit(maxResults);
        }

        return stream.toList();
    }

    @Override
    public String formatNoResultFound(WorkspaceSymbolRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
