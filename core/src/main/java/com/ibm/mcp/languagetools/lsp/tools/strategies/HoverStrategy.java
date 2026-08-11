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
import com.ibm.mcp.languagetools.utils.UriUtils;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Strategy for LSP textDocument/hover requests.
 */
public class HoverStrategy extends FilePositionBasedStrategy<HoverParams, Hover> {

    public HoverStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry, LspCapability.HOVER, "Hover");
    }

    @Override
    public HoverParams buildLspParams(FilePositionRequestParams params) {
        HoverParams lspParams = new HoverParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setPosition(new Position(params.getLine(), params.getCharacter()));
        return lspParams;
    }

    @Override
    protected CompletableFuture<Hover> doExecuteRequest(LspServer server, HoverParams lspParams) {
        return server.getLanguageServer()
                .getTextDocumentService()
                .hover(lspParams);
    }

    @Override
    public Hover getEmptyResult() {
        return null;
    }

    @Override
    public boolean isValidResult(Hover result) {
        return result != null && result.getContents() != null;
    }

    @Override
    public String formatResults(FilePositionRequestParams params, List<Hover> results) {
        String cwdUri = LspJsonFormatter.cwdToUriPrefix(params.getCwd());
        List<String> contents = results.stream()
                .map(this::extractContent)
                .filter(s -> s != null && !s.isEmpty())
                .map(s -> UriUtils.stripFileUriPrefix(s, cwdUri))
                .toList();

        if (contents.isEmpty()) {
            return formatNoResultFound(params);
        }

        return LspJsonFormatter.toJson(LspJsonFormatter.hover(contents));
    }

    private String extractContent(Hover hover) {
        if (hover == null || hover.getContents() == null) {
            return null;
        }
        Either<List<Either<String, MarkedString>>, MarkupContent> contents = hover.getContents();
        if (contents.isRight()) {
            MarkupContent markupContent = contents.getRight();
            return markupContent.getValue();
        }
        if (contents.isLeft()) {
            List<Either<String, MarkedString>> parts = contents.getLeft();
            return parts.stream()
                    .map(part -> {
                        if (part.isLeft()) {
                            return part.getLeft();
                        } else {
                            return part.getRight().getValue();
                        }
                    })
                    .collect(Collectors.joining("\n\n"));
        }
        return null;
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
