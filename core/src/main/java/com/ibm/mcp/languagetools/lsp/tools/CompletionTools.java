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
import com.ibm.mcp.languagetools.lsp.tools.strategies.CompletionStrategy;
import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for LSP completion (get code completions).
 */
@ApplicationScoped
public class CompletionTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(name = "get_completions",
          description = "Get code completion suggestions at a specific position in a file. " +
                        "Returns available completions with their kind and details. " +
                        "Example: get_completions(cwd='/home/user/project', fileUri='file:///home/user/project/src/main.py', line=10, character=5)" +
                        ToolArgDescriptions.OPEN_DOCUMENT_HINT)
    public CompletableFuture<String> getCompletions(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {

        FilePositionRequestParams params = new FilePositionRequestParams(cwd, uri, line, character);
        return requestExecutor.execute(
                params,
                new CompletionStrategy(languageRegistry),
                cancellation,
                progress);
    }
}
