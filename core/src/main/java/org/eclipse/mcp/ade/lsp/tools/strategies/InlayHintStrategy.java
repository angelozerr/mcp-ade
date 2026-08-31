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
import org.eclipse.mcp.ade.lsp.tools.params.InlayHintRequestParams;
import org.eclipse.mcp.ade.operation.OperationContext;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.lsp4j.*;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/inlayHint requests.
 */
public class InlayHintStrategy implements LspRequestExecutor.LspRequestStrategy<InlayHintRequestParams, InlayHintParams, List<InlayHint>> {

    private final LanguageRegistry languageRegistry;

    public InlayHintStrategy(LanguageRegistry languageRegistry) {
        this.languageRegistry = languageRegistry;
    }

    @Override
    public LspCapability getCapability() {
        return LspCapability.INLAY_HINT;
    }

    @Override
    public String getTitle() {
        return "Inlay hints";
    }

    @Override
    public CompletableFuture<List<LspServer>> resolveServers(
            LspServerResolver resolver,
            InlayHintRequestParams params,
            ProgressMonitor progressMonitor,
            OperationContext operationContext) {
        LanguageDocument document = languageRegistry.createDocument(params.getFileUri());
        return resolver.getLspServersForFile(
                document,
                params.getCwd(),
                server -> server.isEnabled() && server.supportsCapability(getCapability(), document),
                progressMonitor,
                operationContext
        );
    }

    @Override
    public InlayHintParams buildLspParams(InlayHintRequestParams params) {
        InlayHintParams lspParams = new InlayHintParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setRange(new Range(
                new Position(params.getStartLine(), params.getStartCharacter()),
                new Position(params.getEndLine(), params.getEndCharacter())));
        return lspParams;
    }

    @Override
    public CompletableFuture<List<InlayHint>> executeRequest(LspServer server, InlayHintParams lspParams) {
        String fileUri = lspParams.getTextDocument().getUri();
        String languageId = languageRegistry.detectLanguage(URI.create(fileUri)).orElse("");
        return server.withAutoDidOpen(getCapability(), fileUri, languageId,
                () -> server.getLanguageServer()
                        .getTextDocumentService()
                        .inlayHint(lspParams));
    }

    @Override
    public List<InlayHint> getEmptyResult() {
        return Collections.emptyList();
    }

    @Override
    public boolean isValidResult(List<InlayHint> result) {
        return result != null && !result.isEmpty();
    }

    @Override
    public String formatResults(InlayHintRequestParams params, List<List<InlayHint>> results) {
        List<InlayHint> all = results.stream().flatMap(List::stream).toList();
        if (all.isEmpty()) {
            return formatNoResultFound(params);
        }
        return LspJsonFormatter.toJson(all.stream().map(LspJsonFormatter::inlayHint).toList());
    }

    @Override
    public String formatNoServerFound(InlayHintRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }

    @Override
    public String formatNoResultFound(InlayHintRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
