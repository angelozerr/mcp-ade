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
import com.ibm.mcp.languagetools.lsp.tools.params.FileUriRequestParams;
import com.ibm.mcp.languagetools.lsp.tools.strategies.DocumentSymbolStrategy;
import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class DocumentSymbolTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(name = "get_document_symbols",
          description = "Get all symbols (classes, methods, variables, etc.) in a document. " +
                        "Returns a hierarchical list of symbols with their kind and location. " +
                        "Example: get_document_symbols(cwd='/home/user/project', fileUri='file:///home/user/project/src/main.py')")
    public CompletableFuture<String> getDocumentSymbols(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {
        FileUriRequestParams params = new FileUriRequestParams(cwd, uri);
        return requestExecutor.execute(
                params,
                new DocumentSymbolStrategy(languageRegistry),
                cancellation,
                progress
        );
    }
}
