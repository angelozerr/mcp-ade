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

import org.eclipse.mcp.ade.language.LanguageDocument;
import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.client.LspCapability;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerResolver;
import org.eclipse.mcp.ade.lsp.tools.LspRequestExecutor;
import org.eclipse.mcp.ade.lsp.tools.params.FilePositionRequestParams;
import org.eclipse.mcp.ade.operation.OperationContext;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Base class for file position-based LSP request strategies.
 * Handles server resolution for file-based requests with position.
 *
 * @param <TLspParams> LSP request parameters type
 * @param <TResult>    LSP response type
 */
public abstract class FilePositionBasedStrategy<TLspParams, TResult>
        implements LspRequestExecutor.LspRequestStrategy<FilePositionRequestParams, TLspParams, TResult> {

    protected final LanguageRegistry languageRegistry;
    private final LspCapability capability;
    private final String title;
    private final Supplier<TLspParams> paramsFactory;

    protected FilePositionBasedStrategy(LanguageRegistry languageRegistry, LspCapability capability, String title) {
        this(languageRegistry, capability, title, null);
    }

    protected FilePositionBasedStrategy(LanguageRegistry languageRegistry, LspCapability capability, String title, Supplier<TLspParams> paramsFactory) {
        this.languageRegistry = languageRegistry;
        this.capability = capability;
        this.title = title;
        this.paramsFactory = paramsFactory;
    }

    @Override
    public TLspParams buildLspParams(FilePositionRequestParams params) {
        if (paramsFactory == null) {
            throw new UnsupportedOperationException("Override buildLspParams or provide a paramsFactory");
        }
        TLspParams lspParams = paramsFactory.get();
        if (lspParams instanceof TextDocumentPositionParams p) {
            p.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
            p.setPosition(new Position(params.getLine(), params.getCharacter()));
        }
        return lspParams;
    }

    @Override
    public LspCapability getCapability() {
        return capability;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public CompletableFuture<List<LspServer>> resolveServers(
            LspServerResolver resolver,
            FilePositionRequestParams params,
            ProgressMonitor progressMonitor,
            OperationContext operationContext) {

        // Create language document (detects language once)
        LanguageDocument document = languageRegistry.createDocument(params.getFileUri());

        // Resolve servers that support this capability for this file
        return resolver.getLspServersForFile(
                document,
                params.getCwd(),
                server -> server.isEnabled() && server.supportsCapability(getCapability(), document),
                progressMonitor,
                operationContext
        );
    }

    @Override
    public final CompletableFuture<TResult> executeRequest(LspServer server, TLspParams lspParams) {
        String fileUri = extractFileUri(lspParams);
        if (fileUri == null) {
            return doExecuteRequest(server, lspParams);
        }
        String languageId = languageRegistry.detectLanguage(URI.create(fileUri)).orElse("");
        return server.withAutoDidOpen(capability, fileUri, languageId,
                () -> doExecuteRequest(server, lspParams));
    }

    protected abstract CompletableFuture<TResult> doExecuteRequest(LspServer server, TLspParams lspParams);

    protected String extractFileUri(TLspParams lspParams) {
        if (lspParams instanceof TextDocumentPositionParams p) {
            return p.getTextDocument().getUri();
        }
        return null;
    }

    @Override
    public String formatNoServerFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }

    @Override
    public String formatError(FilePositionRequestParams params, Throwable ex) {
        return "Failed to execute request: " + ex.getMessage();
    }
}
