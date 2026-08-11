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
import com.ibm.mcp.languagetools.lsp.tools.strategies.ImplementationStrategy;
import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for LSP implementation (go to implementation).
 */
@ApplicationScoped
public class ImplementationTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    SymbolNameResolver symbolNameResolver;

    @Tool(name = "find_implementations",
          description = "Find all implementations of an interface or abstract class. " +
                        "Accepts either symbolName (e.g., 'MyInterface') or uri+line+character position. " +
                        "Returns the locations where the symbol is implemented. " +
                        "Example with symbolName: find_implementations(cwd='/home/user/project', symbolName='MyInterface') " +
                        "Example with position: find_implementations(cwd='/home/user/project', uri='file:///src/MyInterface.java', line=5, character=15)" +
                        ToolArgDescriptions.OPEN_DOCUMENT_HINT)
    public CompletableFuture<String> findImplementations(
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
                        new ImplementationStrategy(languageRegistry),
                        cancellation,
                        progress));
    }
}
