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
import com.ibm.mcp.languagetools.lsp.tools.params.FileUriRequestParams;
import com.ibm.mcp.languagetools.operation.OperationContext;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import org.eclipse.lsp4j.*;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/codeLens requests.
 */
public class CodeLensStrategy implements LspRequestExecutor.LspRequestStrategy<FileUriRequestParams, CodeLensParams, List<? extends CodeLens>> {

    private final LanguageRegistry languageRegistry;

    public CodeLensStrategy(LanguageRegistry languageRegistry) {
        this.languageRegistry = languageRegistry;
    }

    @Override
    public LspCapability getCapability() {
        return LspCapability.CODE_LENS;
    }

    @Override
    public String getTitle() {
        return "Code lenses";
    }

    @Override
    public CompletableFuture<List<LspServer>> resolveServers(
            LspServerResolver resolver,
            FileUriRequestParams params,
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
    public CodeLensParams buildLspParams(FileUriRequestParams params) {
        CodeLensParams lspParams = new CodeLensParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        return lspParams;
    }

    @Override
    public CompletableFuture<List<? extends CodeLens>> executeRequest(LspServer server, CodeLensParams lspParams) {
        String fileUri = lspParams.getTextDocument().getUri();
        String languageId = languageRegistry.detectLanguage(URI.create(fileUri)).orElse("");
        return server.withAutoDidOpen(getCapability(), fileUri, languageId,
                () -> server.getLanguageServer()
                        .getTextDocumentService()
                        .codeLens(lspParams));
    }

    @Override
    public List<? extends CodeLens> getEmptyResult() {
        return Collections.emptyList();
    }

    @Override
    public boolean isValidResult(List<? extends CodeLens> result) {
        return result != null && !result.isEmpty();
    }

    @Override
    public String formatResults(FileUriRequestParams params, List<List<? extends CodeLens>> results) {
        List<CodeLens> all = results.stream().flatMap(List::stream).map(cl -> (CodeLens) cl).toList();
        if (all.isEmpty()) {
            return formatNoResultFound(params);
        }
        return LspJsonFormatter.toJson(all.stream().map(LspJsonFormatter::codeLens).toList());
    }

    @Override
    public String formatNoServerFound(FileUriRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }

    @Override
    public String formatNoResultFound(FileUriRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
