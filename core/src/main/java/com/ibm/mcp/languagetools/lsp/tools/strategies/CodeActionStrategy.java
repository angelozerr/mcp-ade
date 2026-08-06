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
import com.ibm.mcp.languagetools.lsp.tools.params.FilePositionRequestParams;
import com.ibm.mcp.languagetools.operation.OperationContext;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class CodeActionStrategy
        extends DidOpenBasedStrategy<FilePositionRequestParams, CodeActionParams, List<Either<Command, CodeAction>>> {

    public CodeActionStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry);
    }

    @Override
    public LspCapability getCapability() {
        return LspCapability.CODE_ACTION;
    }

    @Override
    public String getTitle() {
        return "Code actions";
    }

    @Override
    public CompletableFuture<List<LspServer>> resolveServers(
            LspServerResolver resolver,
            FilePositionRequestParams params,
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
    public CodeActionParams buildLspParams(FilePositionRequestParams params) {
        CodeActionParams lspParams = new CodeActionParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        Position pos = new Position(params.getLine(), params.getCharacter());
        lspParams.setRange(new Range(pos, pos));
        lspParams.setContext(new CodeActionContext(Collections.emptyList()));
        return lspParams;
    }

    @Override
    protected boolean autoClose() {
        return false;
    }

    @Override
    protected String extractFileUri(CodeActionParams lspParams) {
        return lspParams.getTextDocument().getUri();
    }

    @Override
    protected CompletableFuture<List<Either<Command, CodeAction>>> executeAfterDiagnostics(
            LspServer server, CodeActionParams lspParams) {
        String fileUri = lspParams.getTextDocument().getUri();
        Position pos = lspParams.getRange().getStart();
        List<Diagnostic> cachedDiagnostics = server.getDiagnosticsCache().get(fileUri);
        List<Diagnostic> relevantDiagnostics = filterDiagnosticsAtPosition(cachedDiagnostics, pos);

        Range range = relevantDiagnostics.isEmpty()
                ? lspParams.getRange()
                : relevantDiagnostics.get(0).getRange();

        CodeActionParams enrichedParams = new CodeActionParams();
        enrichedParams.setTextDocument(lspParams.getTextDocument());
        enrichedParams.setRange(range);
        enrichedParams.setContext(new CodeActionContext(relevantDiagnostics));

        String languageId = languageRegistry.detectLanguage(URI.create(fileUri)).orElse("");
        return server.getCodeActions(enrichedParams, languageId);
    }

    private List<Diagnostic> filterDiagnosticsAtPosition(List<Diagnostic> diagnostics, Position pos) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return Collections.emptyList();
        }
        return diagnostics.stream()
                .filter(d -> containsPosition(d.getRange(), pos))
                .collect(Collectors.toList());
    }

    private boolean containsPosition(Range range, Position pos) {
        if (pos.getLine() < range.getStart().getLine() || pos.getLine() > range.getEnd().getLine()) return false;
        if (pos.getLine() == range.getStart().getLine() && pos.getCharacter() < range.getStart().getCharacter()) return false;
        if (pos.getLine() == range.getEnd().getLine() && pos.getCharacter() > range.getEnd().getCharacter()) return false;
        return true;
    }

    @Override
    public List<Either<Command, CodeAction>> getEmptyResult() {
        return Collections.emptyList();
    }

    @Override
    public boolean isValidResult(List<Either<Command, CodeAction>> result) {
        return result != null && !result.isEmpty();
    }

    @Override
    public String formatResults(FilePositionRequestParams params, List<List<Either<Command, CodeAction>>> results) {
        List<Either<Command, CodeAction>> allActions = results.stream()
                .flatMap(List::stream)
                .toList();

        StringBuilder result = new StringBuilder();
        result.append(String.format("Found %d code action(s) at %s:%d:%d\n\n",
                allActions.size(), params.getFileUri(), params.getLine() + 1, params.getCharacter()));

        for (int i = 0; i < allActions.size(); i++) {
            Either<Command, CodeAction> item = allActions.get(i);
            if (item.isRight()) {
                CodeAction action = item.getRight();
                result.append(String.format("%d. %s", i + 1, action.getTitle()));
                if (action.getKind() != null) {
                    result.append(String.format(" [%s]", action.getKind()));
                }
                result.append("\n");

                if (action.getDiagnostics() != null && !action.getDiagnostics().isEmpty()) {
                    for (Diagnostic diag : action.getDiagnostics()) {
                        result.append(String.format("   Diagnostic: %s (line %d)\n",
                                diag.getMessage(),
                                diag.getRange().getStart().getLine() + 1));
                    }
                }

                if (action.getEdit() != null) {
                    formatWorkspaceEdit(result, action.getEdit(), "   ");
                }
            } else {
                Command command = item.getLeft();
                result.append(String.format("%d. %s (command: %s)\n", i + 1, command.getTitle(), command.getCommand()));
            }
        }

        return result.toString();
    }

    private void formatWorkspaceEdit(StringBuilder result, WorkspaceEdit edit, String indent) {
        if (edit.getChanges() != null && !edit.getChanges().isEmpty()) {
            for (Map.Entry<String, List<TextEdit>> entry : edit.getChanges().entrySet()) {
                result.append(String.format("%sFile: %s (%d edit(s))\n", indent, entry.getKey(), entry.getValue().size()));
                for (TextEdit textEdit : entry.getValue()) {
                    Range range = textEdit.getRange();
                    result.append(String.format("%s  Line %d:%d-%d:%d -> \"%s\"\n",
                            indent,
                            range.getStart().getLine() + 1, range.getStart().getCharacter(),
                            range.getEnd().getLine() + 1, range.getEnd().getCharacter(),
                            truncate(textEdit.getNewText(), 80)));
                }
            }
        }
        if (edit.getDocumentChanges() != null && !edit.getDocumentChanges().isEmpty()) {
            for (Either<TextDocumentEdit, ResourceOperation> change : edit.getDocumentChanges()) {
                if (change.isLeft()) {
                    TextDocumentEdit docEdit = change.getLeft();
                    result.append(String.format("%sFile: %s (%d edit(s))\n",
                            indent, docEdit.getTextDocument().getUri(), docEdit.getEdits().size()));
                    for (Either<TextEdit, SnippetTextEdit> textEdit : docEdit.getEdits()) {
                        if (!textEdit.isLeft()) continue;
                        TextEdit te = textEdit.getLeft();
                        Range range = te.getRange();
                        result.append(String.format("%s  Line %d:%d-%d:%d -> \"%s\"\n",
                                indent,
                                range.getStart().getLine() + 1, range.getStart().getCharacter(),
                                range.getEnd().getLine() + 1, range.getEnd().getCharacter(),
                                truncate(te.getNewText(), 80)));
                    }
                } else {
                    ResourceOperation op = change.getRight();
                    result.append(String.format("%sResource operation: %s\n", indent, op.getKind()));
                }
            }
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        String oneLine = text.replace("\n", "\\n").replace("\r", "");
        if (oneLine.length() > maxLen) {
            return oneLine.substring(0, maxLen) + "...";
        }
        return oneLine;
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return String.format("No code actions available at %s:%d:%d",
                params.getFileUri(), params.getLine() + 1, params.getCharacter());
    }
}
