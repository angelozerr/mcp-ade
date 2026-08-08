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
import com.ibm.mcp.languagetools.lsp.tools.params.RenameRequestParams;
import com.ibm.mcp.languagetools.operation.OperationContext;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RenameStrategy implements LspRequestExecutor.LspRequestStrategy<RenameRequestParams, RenameParams, WorkspaceEdit> {

    private final LanguageRegistry languageRegistry;

    public RenameStrategy(LanguageRegistry languageRegistry) {
        this.languageRegistry = languageRegistry;
    }

    @Override
    public LspCapability getCapability() {
        return LspCapability.RENAME;
    }

    @Override
    public String getTitle() {
        return "Rename symbol";
    }

    @Override
    public CompletableFuture<List<LspServer>> resolveServers(
            LspServerResolver resolver,
            RenameRequestParams params,
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
    public RenameParams buildLspParams(RenameRequestParams params) {
        RenameParams lspParams = new RenameParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setPosition(new Position(params.getLine(), params.getCharacter()));
        lspParams.setNewName(params.getNewName());
        return lspParams;
    }

    @Override
    public CompletableFuture<WorkspaceEdit> executeRequest(LspServer server, RenameParams lspParams) {
        String fileUri = lspParams.getTextDocument().getUri();
        String languageId = languageRegistry.detectLanguage(URI.create(fileUri)).orElse("");
        return server.withAutoDidOpen(LspCapability.RENAME, fileUri, languageId,
                () -> server.getLanguageServer()
                        .getTextDocumentService()
                        .rename(lspParams));
    }

    @Override
    public WorkspaceEdit getEmptyResult() {
        return new WorkspaceEdit();
    }

    @Override
    public boolean isValidResult(WorkspaceEdit result) {
        if (result == null) return false;
        boolean hasChanges = result.getChanges() != null && !result.getChanges().isEmpty();
        boolean hasDocChanges = result.getDocumentChanges() != null && !result.getDocumentChanges().isEmpty();
        return hasChanges || hasDocChanges;
    }

    @Override
    public String formatResults(RenameRequestParams params, List<WorkspaceEdit> results) {
        return LspJsonFormatter.toJson(LspJsonFormatter.workspaceEdits(results));
    }

    @Override
    public String formatNoServerFound(RenameRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }

    @Override
    public String formatNoResultFound(RenameRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
