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
import org.eclipse.mcp.ade.lsp.tools.params.WorkspaceSymbolRequestParams;
import org.eclipse.mcp.ade.lsp.tools.strategies.WorkspaceSymbolStrategy;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for LSP workspace/symbol.
 */
@ApplicationScoped
public class WorkspaceSymbolTools {

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    LspRequestExecutor requestExecutor;

    @Tool(name = "search_workspace_symbols",
          description = "Search for symbols across the workspace. Filter by kind, pathPattern, or containerName.")
    public CompletableFuture<String> searchWorkspaceSymbols(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "The search query string to match against symbol names") String query,
            @ToolArg(description = ToolArgDescriptions.SYMBOL_KIND, required = false) String kind,
            @ToolArg(description = ToolArgDescriptions.PATH_PATTERN, required = false) String pathPattern,
            @ToolArg(description = ToolArgDescriptions.CONTAINER_NAME, required = false) String containerName,
            @ToolArg(description = ToolArgDescriptions.MAX_RESULTS, required = false) Integer maxResults,
            @ToolArg(description = ToolArgDescriptions.FILE_EXT, required = false) String fileExt,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {

        WorkspaceSymbolRequestParams params = new WorkspaceSymbolRequestParams(cwd, query);
        params.setKind(kind);
        params.setPathPattern(pathPattern);
        params.setContainerName(containerName);
        params.setMaxResults(maxResults);
        params.setFileExt(fileExt);
        return requestExecutor.executeAsString(
                params,
                new WorkspaceSymbolStrategy(languageRegistry),
                cancellation,
                progress);
    }
}
