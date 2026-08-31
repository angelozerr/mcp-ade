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

public class TypeHierarchySubtypesStrategy
        extends FilePositionBasedStrategy<TypeHierarchyPrepareParams, List<TypeHierarchyItem>> {

    public TypeHierarchySubtypesStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry, LspCapability.TYPE_HIERARCHY, "Type hierarchy subtypes");
    }

    @Override
    public TypeHierarchyPrepareParams buildLspParams(FilePositionRequestParams params) {
        TypeHierarchyPrepareParams lspParams = new TypeHierarchyPrepareParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setPosition(new Position(params.getLine(), params.getCharacter()));
        return lspParams;
    }

    @Override
    protected CompletableFuture<List<TypeHierarchyItem>> doExecuteRequest(
            LspServer server, TypeHierarchyPrepareParams lspParams) {
        return server.getLanguageServer()
                .getTextDocumentService()
                .prepareTypeHierarchy(lspParams)
                .thenCompose(items -> {
                    if (items == null || items.isEmpty()) {
                        return CompletableFuture.completedFuture(Collections.emptyList());
                    }
                    TypeHierarchySubtypesParams subtypesParams = new TypeHierarchySubtypesParams();
                    subtypesParams.setItem(items.get(0));
                    return server.getLanguageServer()
                            .getTextDocumentService()
                            .typeHierarchySubtypes(subtypesParams);
                });
    }

    @Override
    protected String extractFileUri(TypeHierarchyPrepareParams lspParams) {
        return lspParams.getTextDocument().getUri();
    }

    @Override
    public List<TypeHierarchyItem> getEmptyResult() {
        return Collections.emptyList();
    }

    @Override
    public boolean isValidResult(List<TypeHierarchyItem> result) {
        return result != null && !result.isEmpty();
    }

    @Override
    public String formatResults(FilePositionRequestParams params, List<List<TypeHierarchyItem>> results) {
        String cwdUri = LspJsonFormatter.cwdToUriPrefix(params.getCwd());
        List<Map<String, Object>> items = results.stream()
                .flatMap(List::stream)
                .map(item -> LspJsonFormatter.typeHierarchyItem(item, cwdUri))
                .toList();
        return LspJsonFormatter.toJson(items);
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
