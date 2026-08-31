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
import org.eclipse.mcp.ade.lsp.tools.strategies.HoverStrategy;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for LSP hover (get hover information).
 */
@ApplicationScoped
public class HoverTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    SymbolNameResolver symbolNameResolver;

    @Tool(name = "get_hover_info",
          description = "Get hover information (type, documentation) for a symbol. " +
                        "Accepts either symbolName (e.g., 'myMethod', 'MyClass.myMethod') or uri+line+character position. " +
                        "Returns type information and documentation if available. " +
                        "Example with symbolName: get_hover_info(cwd='/home/user/project', symbolName='myMethod') " +
                        "Example with position: get_hover_info(cwd='/home/user/project', uri='file:///src/main.py', line=10, character=5)" +
                        ToolArgDescriptions.OPEN_DOCUMENT_HINT)
    public CompletableFuture<String> getHoverInfo(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.SYMBOL_NAME, required = false) String symbolName,
            @ToolArg(description = ToolArgDescriptions.URI, required = false) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE, required = false) Integer line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER, required = false) Integer character,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {

        return symbolNameResolver.resolveParams(cwd, symbolName, uri, line, character)
                .thenCompose(params -> requestExecutor.executeAsString(
                        params,
                        new HoverStrategy(languageRegistry),
                        cancellation,
                        progress));
    }
}
