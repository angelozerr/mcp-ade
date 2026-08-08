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
import com.ibm.mcp.languagetools.lsp.tools.params.InlayHintRequestParams;
import com.ibm.mcp.languagetools.lsp.tools.strategies.InlayHintStrategy;
import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class InlayHintTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(name = "get_inlay_hints",
          description = "Get inlay hints for a range in a document. Inlay hints show inferred types, parameter names, and other inline annotations. " +
                        "Example: get_inlay_hints(cwd='/home/user/project', fileUri='file:///home/user/project/src/main.py', startLine=0, startCharacter=0, endLine=50, endCharacter=0)")
    public CompletableFuture<String> getInlayHints(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = "Start line of the range (0-based)") int startLine,
            @ToolArg(description = "Start character of the range (0-based)") int startCharacter,
            @ToolArg(description = "End line of the range (0-based)") int endLine,
            @ToolArg(description = "End character of the range (0-based)") int endCharacter,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {
        InlayHintRequestParams params = new InlayHintRequestParams(cwd, uri, startLine, startCharacter, endLine, endCharacter);
        return requestExecutor.execute(
                params,
                new InlayHintStrategy(languageRegistry),
                cancellation,
                progress
        );
    }
}
