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

import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.client.LspCapability;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.tools.params.FilePositionRequestParams;
import org.eclipse.lsp4j.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class CallHierarchyIncomingCallsStrategy
        extends FilePositionBasedStrategy<CallHierarchyPrepareParams, List<CallHierarchyIncomingCall>> {

    public CallHierarchyIncomingCallsStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry, LspCapability.CALL_HIERARCHY, "Call hierarchy incoming");
    }

    @Override
    public CallHierarchyPrepareParams buildLspParams(FilePositionRequestParams params) {
        CallHierarchyPrepareParams lspParams = new CallHierarchyPrepareParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setPosition(new Position(params.getLine(), params.getCharacter()));
        return lspParams;
    }

    @Override
    protected CompletableFuture<List<CallHierarchyIncomingCall>> doExecuteRequest(
            LspServer server, CallHierarchyPrepareParams lspParams) {
        return server.getLanguageServer()
                .getTextDocumentService()
                .prepareCallHierarchy(lspParams)
                .thenCompose(items -> {
                    if (items == null || items.isEmpty()) {
                        return CompletableFuture.completedFuture(Collections.emptyList());
                    }
                    CallHierarchyIncomingCallsParams incomingParams = new CallHierarchyIncomingCallsParams();
                    incomingParams.setItem(items.get(0));
                    return server.getLanguageServer()
                            .getTextDocumentService()
                            .callHierarchyIncomingCalls(incomingParams);
                });
    }

    @Override
    protected String extractFileUri(CallHierarchyPrepareParams lspParams) {
        return lspParams.getTextDocument().getUri();
    }

    @Override
    public List<CallHierarchyIncomingCall> getEmptyResult() {
        return Collections.emptyList();
    }

    @Override
    public boolean isValidResult(List<CallHierarchyIncomingCall> result) {
        return result != null && !result.isEmpty();
    }

    @Override
    public String formatResults(FilePositionRequestParams params, List<List<CallHierarchyIncomingCall>> results) {
        String cwdUri = LspJsonFormatter.cwdToUriPrefix(params.getCwd());
        List<Map<String, Object>> items = results.stream()
                .flatMap(List::stream)
                .map(call -> LspJsonFormatter.callHierarchyIncomingCall(call, cwdUri))
                .toList();
        return LspJsonFormatter.toJson(items);
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
