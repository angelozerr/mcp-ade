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

import com.ibm.mcp.languagetools.language.LanguageRegistry;
import com.ibm.mcp.languagetools.lsp.client.LspCapability;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.tools.params.FilePositionRequestParams;
import org.eclipse.lsp4j.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Strategy for LSP textDocument/references requests.
 */
public class ReferencesStrategy extends FilePositionBasedStrategy<ReferenceParams, List<? extends Location>> {

    public ReferencesStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry, LspCapability.REFERENCES, "Find references");
    }

    @Override
    public ReferenceParams buildLspParams(FilePositionRequestParams params) {
        ReferenceParams lspParams = new ReferenceParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setPosition(new Position(params.getLine(), params.getCharacter()));

        ReferenceContext context = new ReferenceContext();
        context.setIncludeDeclaration(true); // Include the declaration itself
        lspParams.setContext(context);

        return lspParams;
    }

    @Override
    protected CompletableFuture<List<? extends Location>> doExecuteRequest(LspServer server, ReferenceParams lspParams) {
        return server.getLanguageServer()
                .getTextDocumentService()
                .references(lspParams);
    }

    @Override
    public List<? extends Location> getEmptyResult() {
        return Collections.emptyList();
    }

    @Override
    public boolean isValidResult(List<? extends Location> result) {
        return result != null && !result.isEmpty();
    }

    @Override
    public String formatResults(FilePositionRequestParams params, List<List<? extends Location>> results) {
        // Merge all results
        List<? extends Location> allReferences = results.stream()
                .flatMap(List::stream)
                .toList();

        // Deduplicate based on URI + range
        List<? extends Location> references = allReferences.stream()
                .distinct()
                .toList();

        return LspJsonFormatter.toJson(references.stream()
                .map(loc -> LspJsonFormatter.location((Location) loc))
                .toList());
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}

