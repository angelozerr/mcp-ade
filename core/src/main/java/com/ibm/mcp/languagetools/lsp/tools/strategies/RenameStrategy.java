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
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Rename to '%s' at %s:%d:%d\n\n",
                params.getNewName(), params.getFileUri(), params.getLine() + 1, params.getCharacter()));

        int totalEdits = 0;

        for (WorkspaceEdit edit : results) {
            if (edit.getChanges() != null) {
                for (Map.Entry<String, List<TextEdit>> entry : edit.getChanges().entrySet()) {
                    String fileUri = entry.getKey();
                    List<TextEdit> edits = entry.getValue();
                    totalEdits += edits.size();
                    sb.append(String.format("File: %s (%d edit(s))\n", fileUri, edits.size()));
                    for (TextEdit te : edits) {
                        Range range = te.getRange();
                        sb.append(String.format("  Line %d:%d-%d:%d -> \"%s\"\n",
                                range.getStart().getLine() + 1, range.getStart().getCharacter(),
                                range.getEnd().getLine() + 1, range.getEnd().getCharacter(),
                                te.getNewText()));
                    }
                    sb.append("\n");
                }
            }

            if (edit.getDocumentChanges() != null) {
                for (Either<TextDocumentEdit, ResourceOperation> change : edit.getDocumentChanges()) {
                    if (change.isLeft()) {
                        TextDocumentEdit docEdit = change.getLeft();
                        String fileUri = docEdit.getTextDocument().getUri();
                        int editCount = docEdit.getEdits().size();
                        totalEdits += editCount;
                        sb.append(String.format("File: %s (%d edit(s))\n", fileUri, editCount));
                        for (Either<TextEdit, SnippetTextEdit> textEdit : docEdit.getEdits()) {
                            if (!textEdit.isLeft()) continue;
                            TextEdit te = textEdit.getLeft();
                            Range range = te.getRange();
                            sb.append(String.format("  Line %d:%d-%d:%d -> \"%s\"\n",
                                    range.getStart().getLine() + 1, range.getStart().getCharacter(),
                                    range.getEnd().getLine() + 1, range.getEnd().getCharacter(),
                                    te.getNewText()));
                        }
                        sb.append("\n");
                    } else {
                        ResourceOperation op = change.getRight();
                        sb.append(String.format("Resource operation: %s\n", op.getKind()));
                    }
                }
            }
        }

        sb.insert(sb.indexOf("\n\n") + 2, String.format("Total: %d edit(s)\n\n", totalEdits));
        return sb.toString();
    }

    @Override
    public String formatNoServerFound(RenameRequestParams params) {
        return String.format("No language server with rename support found for: %s", params.getFileUri());
    }

    @Override
    public String formatNoResultFound(RenameRequestParams params) {
        return String.format("Cannot rename symbol at %s:%d:%d to '%s'",
                params.getFileUri(), params.getLine() + 1, params.getCharacter(), params.getNewName());
    }
}
