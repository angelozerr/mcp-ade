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

import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.tools.LspRequestExecutor;
import org.eclipse.mcp.ade.lsp.tools.params.FileUriRequestParams;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Base strategy for tools that need file diagnostics.
 * Calls server.getDiagnostics(uri, languageId, autoClose) which handles
 * custom requests (e.g. MicroProfileLspServer) or didOpen fallback.
 *
 * @param <TRequestParams> Tool request params (must extend FileUriRequestParams)
 * @param <TLspParams>     LSP params type
 * @param <TResult>        Result type
 */
public abstract class DidOpenBasedStrategy<TRequestParams extends FileUriRequestParams, TLspParams, TResult>
        implements LspRequestExecutor.LspRequestStrategy<TRequestParams, TLspParams, TResult> {

    private static final Logger LOG = Logger.getLogger(DidOpenBasedStrategy.class);

    protected final LanguageRegistry languageRegistry;

    protected DidOpenBasedStrategy(LanguageRegistry languageRegistry) {
        this.languageRegistry = languageRegistry;
    }

    /**
     * Extract the file URI from the LSP params.
     */
    protected abstract String extractFileUri(TLspParams lspParams);

    /**
     * Execute the tool logic after diagnostics are available in the server cache.
     */
    protected abstract CompletableFuture<TResult> executeAfterDiagnostics(LspServer server, TLspParams lspParams);

    /**
     * Whether to auto-close the file after getting diagnostics.
     * Override to return false when the file must stay open (e.g. code actions).
     */
    protected boolean autoClose() {
        return true;
    }

    @Override
    public final CompletableFuture<TResult> executeRequest(LspServer server, TLspParams lspParams) {
        String fileUri = extractFileUri(lspParams);
        String languageId = languageRegistry.detectLanguage(URI.create(fileUri)).orElse("");

        return server.getDiagnostics(fileUri, languageId, autoClose())
                .thenCompose(diags -> executeAfterDiagnostics(server, lspParams))
                .whenComplete((result, ex) -> {
                    if (!autoClose() && server.isFileOpened(fileUri)) {
                        server.closeFile(fileUri);
                    }
                });
    }
}
