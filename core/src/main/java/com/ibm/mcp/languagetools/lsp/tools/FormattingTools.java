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
import com.ibm.mcp.languagetools.lsp.tools.params.FormattingRequestParams;
import com.ibm.mcp.languagetools.lsp.tools.params.RangeFormattingRequestParams;
import com.ibm.mcp.languagetools.lsp.tools.strategies.FormattingStrategy;
import com.ibm.mcp.languagetools.lsp.tools.strategies.RangeFormattingStrategy;
import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class FormattingTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(name = "format_document",
          description = "Format an entire document using the language server's formatting capabilities. " +
                        "Returns the text edits to apply, or applies them directly when apply=true. " +
                        "Example: format_document(cwd='/home/user/project', fileUri='file:///home/user/project/src/main.py', tabSize=4, insertSpaces=true)")
    public CompletableFuture<String> formatDocument(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = "Tab size (default: 4)") int tabSize,
            @ToolArg(description = "Use spaces instead of tabs (default: true)") boolean insertSpaces,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {
        FormattingRequestParams params = new FormattingRequestParams(cwd, uri, tabSize, insertSpaces, apply != null && apply);
        return requestExecutor.executeAsString(
                params,
                new FormattingStrategy(languageRegistry),
                cancellation,
                progress
        );
    }

    @Tool(name = "format_document_range",
          description = "Format a specific range in a document using the language server's formatting capabilities. " +
                        "Returns the text edits to apply, or applies them directly when apply=true. " +
                        "Example: format_document_range(cwd='/home/user/project', fileUri='file:///home/user/project/src/main.py', tabSize=4, insertSpaces=true, startLine=5, startCharacter=0, endLine=15, endCharacter=0)")
    public CompletableFuture<String> formatDocumentRange(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = "Tab size (default: 4)") int tabSize,
            @ToolArg(description = "Use spaces instead of tabs (default: true)") boolean insertSpaces,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            @ToolArg(description = "Start line of the range (0-based)") int startLine,
            @ToolArg(description = "Start character of the range (0-based)") int startCharacter,
            @ToolArg(description = "End line of the range (0-based)") int endLine,
            @ToolArg(description = "End character of the range (0-based)") int endCharacter,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {
        RangeFormattingRequestParams params = new RangeFormattingRequestParams(cwd, uri, tabSize, insertSpaces, apply != null && apply, startLine, startCharacter, endLine, endCharacter);
        return requestExecutor.executeAsString(
                params,
                new RangeFormattingStrategy(languageRegistry),
                cancellation,
                progress
        );
    }
}
