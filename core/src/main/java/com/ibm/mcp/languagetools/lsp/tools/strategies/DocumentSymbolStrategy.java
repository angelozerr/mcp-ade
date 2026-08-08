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
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/documentSymbol requests.
 */
public class DocumentSymbolStrategy implements LspRequestExecutor.LspRequestStrategy<FileUriRequestParams, DocumentSymbolParams, List<Either<SymbolInformation, DocumentSymbol>>> {

    private final LanguageRegistry languageRegistry;

    public DocumentSymbolStrategy(LanguageRegistry languageRegistry) {
        this.languageRegistry = languageRegistry;
    }

    @Override
    public LspCapability getCapability() {
        return LspCapability.DOCUMENT_SYMBOL;
    }

    @Override
    public String getTitle() {
        return "Document symbols";
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
    public DocumentSymbolParams buildLspParams(FileUriRequestParams params) {
        DocumentSymbolParams lspParams = new DocumentSymbolParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        return lspParams;
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> executeRequest(LspServer server, DocumentSymbolParams lspParams) {
        String fileUri = lspParams.getTextDocument().getUri();
        String languageId = languageRegistry.detectLanguage(URI.create(fileUri)).orElse("");
        return server.withAutoDidOpen(getCapability(), fileUri, languageId,
                () -> server.getLanguageServer()
                        .getTextDocumentService()
                        .documentSymbol(lspParams));
    }

    @Override
    public List<Either<SymbolInformation, DocumentSymbol>> getEmptyResult() {
        return Collections.emptyList();
    }

    @Override
    public boolean isValidResult(List<Either<SymbolInformation, DocumentSymbol>> result) {
        return result != null && !result.isEmpty();
    }

    @Override
    public String formatResults(FileUriRequestParams params, List<List<Either<SymbolInformation, DocumentSymbol>>> results) {
        String cwdUri = LspJsonFormatter.cwdToUriPrefix(params.getCwd());
        List<Map<String, Object>> hierarchical = new java.util.ArrayList<>();
        List<Map<String, Object>> flat = new java.util.ArrayList<>();
        for (List<Either<SymbolInformation, DocumentSymbol>> resultList : results) {
            for (Either<SymbolInformation, DocumentSymbol> either : resultList) {
                if (either.isRight()) {
                    hierarchical.add(LspJsonFormatter.documentSymbol(either.getRight()));
                } else if (either.isLeft()) {
                    flat.add(LspJsonFormatter.symbolInfo(either.getLeft(), cwdUri));
                }
            }
        }
        List<Map<String, Object>> symbols = !hierarchical.isEmpty() ? hierarchical : flat;
        if (symbols.isEmpty()) {
            return formatNoResultFound(params);
        }
        return LspJsonFormatter.toJson(symbols);
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
