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
import com.ibm.mcp.languagetools.lsp.tools.strategies.HoverStrategy;
import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
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

    @Tool(name = "get_hover_info",
          description = "Get hover information (type, documentation) for a symbol at a specific position in a file. " +
                        "Returns type information and documentation if available. " +
                        "Example: get_hover_info(cwd='/home/user/project', fileUri='file:///home/user/project/src/main.py', line=10, character=5)" +
                        ToolArgDescriptions.OPEN_DOCUMENT_HINT)
    public CompletableFuture<String> getHoverInfo(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {

        FilePositionRequestParams params = new FilePositionRequestParams(cwd, uri, line, character);
        return requestExecutor.executeAsString(
                params,
                new HoverStrategy(languageRegistry),
                cancellation,
                progress);
    }
}
