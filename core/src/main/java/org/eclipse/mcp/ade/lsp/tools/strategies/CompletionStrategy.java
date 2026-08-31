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
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/completion requests.
 */
public class CompletionStrategy extends FilePositionBasedStrategy<CompletionParams, Either<List<CompletionItem>, CompletionList>> {

    private static final int DEFAULT_MAX_ITEMS = 10;

    private final int maxItems;

    public CompletionStrategy(LanguageRegistry languageRegistry, Integer maxResults) {
        super(languageRegistry, LspCapability.COMPLETION, "Completion", CompletionParams::new);
        this.maxItems = maxResults != null && maxResults > 0 ? maxResults : DEFAULT_MAX_ITEMS;
    }

    @Override
    protected CompletableFuture<Either<List<CompletionItem>, CompletionList>> doExecuteRequest(
            LspServer server, CompletionParams lspParams) {
        return server.getLanguageServer()
                .getTextDocumentService()
                .completion(lspParams);
    }

    @Override
    public Either<List<CompletionItem>, CompletionList> getEmptyResult() {
        return Either.forLeft(Collections.emptyList());
    }

    @Override
    public boolean isValidResult(Either<List<CompletionItem>, CompletionList> result) {
        if (result == null) return false;
        if (result.isLeft()) return !result.getLeft().isEmpty();
        if (result.isRight()) return result.getRight().getItems() != null && !result.getRight().getItems().isEmpty();
        return false;
    }

    @Override
    public String formatResults(FilePositionRequestParams params, List<Either<List<CompletionItem>, CompletionList>> results) {
        // Collect all completion items from all results
        List<CompletionItem> allItems = new ArrayList<>();
        for (Either<List<CompletionItem>, CompletionList> result : results) {
            if (result.isLeft()) {
                allItems.addAll(result.getLeft());
            } else if (result.isRight() && result.getRight().getItems() != null) {
                allItems.addAll(result.getRight().getItems());
            }
        }

        if (allItems.isEmpty()) {
            return formatNoResultFound(params);
        }

        int displayCount = Math.min(allItems.size(), maxItems);
        return LspJsonFormatter.toJson(allItems.subList(0, displayCount).stream()
                .map(LspJsonFormatter::completionItem)
                .toList());
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
