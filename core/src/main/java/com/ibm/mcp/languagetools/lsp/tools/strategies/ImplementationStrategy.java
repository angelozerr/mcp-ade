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
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/implementation requests.
 */
public class ImplementationStrategy extends FilePositionBasedStrategy<ImplementationParams, Either<List<? extends Location>, List<? extends LocationLink>>> {

    public ImplementationStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry, LspCapability.IMPLEMENTATION, "Find implementations");
    }

    @Override
    public ImplementationParams buildLspParams(FilePositionRequestParams params) {
        ImplementationParams lspParams = new ImplementationParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setPosition(new Position(params.getLine(), params.getCharacter()));
        return lspParams;
    }

    @Override
    protected CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> doExecuteRequest(
            LspServer server, ImplementationParams lspParams) {
        return server.getLanguageServer()
                .getTextDocumentService()
                .implementation(lspParams);
    }

    @Override
    public Either<List<? extends Location>, List<? extends LocationLink>> getEmptyResult() {
        return Either.forLeft(Collections.emptyList());
    }

    @Override
    public boolean isValidResult(Either<List<? extends Location>, List<? extends LocationLink>> result) {
        if (result == null) return false;
        if (result.isLeft()) return !result.getLeft().isEmpty();
        if (result.isRight()) return !result.getRight().isEmpty();
        return false;
    }

    @Override
    public String formatResults(FilePositionRequestParams params, List<Either<List<? extends Location>, List<? extends LocationLink>>> results) {
        // Merge all locations from all results
        List<Location> allLocations = results.stream()
                .flatMap(either -> {
                    if (either.isLeft()) {
                        return either.getLeft().stream();
                    } else {
                        // Convert LocationLink to Location
                        return either.getRight().stream()
                                .map(link -> new Location(link.getTargetUri(), link.getTargetRange()));
                    }
                })
                .distinct()
                .toList();

        if (allLocations.isEmpty()) {
            return formatNoResultFound(params);
        }

        return LspJsonFormatter.toJson(allLocations.stream().map(LspJsonFormatter::location).toList());
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}

