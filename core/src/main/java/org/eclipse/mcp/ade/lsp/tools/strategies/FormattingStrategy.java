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
import org.eclipse.mcp.ade.lsp.tools.TextEditApplier;
import org.eclipse.mcp.ade.lsp.tools.params.FormattingRequestParams;
import org.eclipse.mcp.ade.operation.OperationContext;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.lsp4j.*;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/formatting requests.
 */
public class FormattingStrategy implements LspRequestExecutor.LspRequestStrategy<FormattingRequestParams, DocumentFormattingParams, List<? extends TextEdit>> {

    private final LanguageRegistry languageRegistry;

    public FormattingStrategy(LanguageRegistry languageRegistry) {
        this.languageRegistry = languageRegistry;
    }

    @Override
    public LspCapability getCapability() {
        return LspCapability.FORMATTING;
    }

    @Override
    public String getTitle() {
        return "Format document";
    }

    @Override
    public CompletableFuture<List<LspServer>> resolveServers(
            LspServerResolver resolver,
            FormattingRequestParams params,
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
    public DocumentFormattingParams buildLspParams(FormattingRequestParams params) {
        DocumentFormattingParams lspParams = new DocumentFormattingParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setOptions(new FormattingOptions(params.getTabSize(), params.isInsertSpaces()));
        return lspParams;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> executeRequest(LspServer server, DocumentFormattingParams lspParams) {
        String fileUri = lspParams.getTextDocument().getUri();
        String languageId = languageRegistry.detectLanguage(URI.create(fileUri)).orElse("");
        return server.withAutoDidOpen(getCapability(), fileUri, languageId,
                () -> server.getLanguageServer()
                        .getTextDocumentService()
                        .formatting(lspParams));
    }

    @Override
    public List<? extends TextEdit> getEmptyResult() {
        return Collections.emptyList();
    }

    @Override
    public boolean isValidResult(List<? extends TextEdit> result) {
        return result != null && !result.isEmpty();
    }

    @Override
    public String formatResults(FormattingRequestParams params, List<List<? extends TextEdit>> results) {
        List<TextEdit> allEdits = results.stream()
                .flatMap(list -> list.stream().map(te -> (TextEdit) te))
                .toList();

        if (params.isApply()) {
            TextEditApplier.applyTextEdits(params.getFileUri(), allEdits);
        }

        return LspJsonFormatter.toJson(LspJsonFormatter.textEditsResult(allEdits, params.isApply()));
    }

    @Override
    public String formatNoServerFound(FormattingRequestParams params) {
        return LspJsonFormatter.toJson(LspJsonFormatter.map("changed", false));
    }

    @Override
    public String formatNoResultFound(FormattingRequestParams params) {
        return LspJsonFormatter.toJson(LspJsonFormatter.map("changed", false));
    }

    /**
     * Truncate text for display, replacing newlines and limiting length.
     */
    static String truncate(String text) {
        if (text == null) return "";
        String escaped = text.replace("\n", "\\n").replace("\r", "");
        if (escaped.length() > 80) {
            return escaped.substring(0, 80) + "...";
        }
        return escaped;
    }
}
