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
package com.ibm.mcp.languagetools.lsp.tools.strategies;

import com.ibm.mcp.languagetools.language.LanguageDocument;
import com.ibm.mcp.languagetools.language.LanguageRegistry;
import com.ibm.mcp.languagetools.lsp.client.LspCapability;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.server.LspServerResolver;
import com.ibm.mcp.languagetools.lsp.tools.LspRequestExecutor;
import com.ibm.mcp.languagetools.lsp.tools.TextEditApplier;
import com.ibm.mcp.languagetools.lsp.tools.params.RangeFormattingRequestParams;
import com.ibm.mcp.languagetools.operation.OperationContext;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import org.eclipse.lsp4j.*;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/rangeFormatting requests.
 */
public class RangeFormattingStrategy implements LspRequestExecutor.LspRequestStrategy<RangeFormattingRequestParams, DocumentRangeFormattingParams, List<? extends TextEdit>> {

    private final LanguageRegistry languageRegistry;

    public RangeFormattingStrategy(LanguageRegistry languageRegistry) {
        this.languageRegistry = languageRegistry;
    }

    @Override
    public LspCapability getCapability() {
        return LspCapability.RANGE_FORMATTING;
    }

    @Override
    public String getTitle() {
        return "Format range";
    }

    @Override
    public CompletableFuture<List<LspServer>> resolveServers(
            LspServerResolver resolver,
            RangeFormattingRequestParams params,
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
    public DocumentRangeFormattingParams buildLspParams(RangeFormattingRequestParams params) {
        DocumentRangeFormattingParams lspParams = new DocumentRangeFormattingParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setOptions(new FormattingOptions(params.getTabSize(), params.isInsertSpaces()));
        lspParams.setRange(new Range(
                new Position(params.getStartLine(), params.getStartCharacter()),
                new Position(params.getEndLine(), params.getEndCharacter())));
        return lspParams;
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> executeRequest(LspServer server, DocumentRangeFormattingParams lspParams) {
        String fileUri = lspParams.getTextDocument().getUri();
        String languageId = languageRegistry.detectLanguage(URI.create(fileUri)).orElse("");
        return server.withAutoDidOpen(getCapability(), fileUri, languageId,
                () -> server.getLanguageServer()
                        .getTextDocumentService()
                        .rangeFormatting(lspParams));
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
    public String formatResults(RangeFormattingRequestParams params, List<List<? extends TextEdit>> results) {
        List<TextEdit> allEdits = results.stream()
                .flatMap(list -> list.stream().map(te -> (TextEdit) te))
                .toList();

        if (params.isApply()) {
            TextEditApplier.applyTextEdits(params.getFileUri(), allEdits);
        }

        return LspJsonFormatter.toJson(LspJsonFormatter.textEditsResult(allEdits, params.isApply()));
    }

    @Override
    public String formatNoServerFound(RangeFormattingRequestParams params) {
        return LspJsonFormatter.toJson(LspJsonFormatter.map("changed", false));
    }

    @Override
    public String formatNoResultFound(RangeFormattingRequestParams params) {
        return LspJsonFormatter.toJson(LspJsonFormatter.map("changed", false));
    }
}
