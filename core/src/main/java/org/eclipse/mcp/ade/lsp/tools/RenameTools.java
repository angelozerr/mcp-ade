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
import org.eclipse.mcp.ade.lsp.tools.params.RenameRequestParams;
import org.eclipse.mcp.ade.lsp.tools.strategies.RenameStrategy;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class RenameTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    SymbolNameResolver symbolNameResolver;

    @Tool(name = "rename",
          description = "Rename a symbol across the entire workspace and apply all changes to disk automatically. " +
                        "Use symbolName (e.g. 'DOMNode.getChildren') or uri+line+character.")
    public CompletableFuture<String> rename(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.SYMBOL_NAME, required = false) String symbolName,
            @ToolArg(description = ToolArgDescriptions.URI, required = false) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE, required = false) Integer line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER, required = false) Integer character,
            @ToolArg(description = "The new name for the symbol") String newName,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            @ToolArg(description = ToolArgDescriptions.FILE_EXT, required = false) String fileExt,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {

        boolean doApply = apply == null || apply;
        return symbolNameResolver.resolveParams(cwd, symbolName, uri, line, character, fileExt)
                .thenCompose(resolvedParams -> {
                    RenameRequestParams params = new RenameRequestParams(
                            resolvedParams.getCwd(),
                            resolvedParams.getFileUri(),
                            resolvedParams.getLine(),
                            resolvedParams.getCharacter(),
                            newName,
                            doApply);
                    return requestExecutor.executeAsString(
                            params,
                            new RenameStrategy(languageRegistry),
                            cancellation,
                            progress);
                });
    }
}
