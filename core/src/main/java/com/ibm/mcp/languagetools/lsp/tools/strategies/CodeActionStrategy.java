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
import com.ibm.mcp.languagetools.utils.UriUtils;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
        List<Diagnostic> cachedDiagnostics = server.getDiagnosticsCache().get(UriUtils.normalizeUri(fileUri));
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
                .toList();
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

        List<Map<String, Object>> items = allActions.stream()
                .map(item -> item.isRight()
                        ? LspJsonFormatter.codeAction(item.getRight())
                        : LspJsonFormatter.command(item.getLeft()))
                .toList();

        return LspJsonFormatter.toJson(items);
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
