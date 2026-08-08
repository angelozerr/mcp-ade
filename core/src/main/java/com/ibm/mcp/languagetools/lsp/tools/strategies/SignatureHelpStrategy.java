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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/signatureHelp requests.
 */
public class SignatureHelpStrategy extends FilePositionBasedStrategy<SignatureHelpParams, SignatureHelp> {

    public SignatureHelpStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry, LspCapability.SIGNATURE_HELP, "Signature help");
    }

    @Override
    public SignatureHelpParams buildLspParams(FilePositionRequestParams params) {
        SignatureHelpParams lspParams = new SignatureHelpParams();
        lspParams.setTextDocument(new TextDocumentIdentifier(params.getFileUri()));
        lspParams.setPosition(new Position(params.getLine(), params.getCharacter()));
        return lspParams;
    }

    @Override
    protected CompletableFuture<SignatureHelp> doExecuteRequest(LspServer server, SignatureHelpParams lspParams) {
        return server.getLanguageServer()
                .getTextDocumentService()
                .signatureHelp(lspParams);
    }

    @Override
    public SignatureHelp getEmptyResult() {
        return null;
    }

    @Override
    public boolean isValidResult(SignatureHelp result) {
        return result != null && result.getSignatures() != null && !result.getSignatures().isEmpty();
    }

    @Override
    public String formatResults(FilePositionRequestParams params, List<SignatureHelp> results) {
        List<Map<String, Object>> allSignatures = new java.util.ArrayList<>();
        for (SignatureHelp signatureHelp : results) {
            Integer activeParameter = signatureHelp.getActiveParameter();
            for (SignatureInformation sig : signatureHelp.getSignatures()) {
                allSignatures.add(LspJsonFormatter.signatureInfo(sig, activeParameter));
            }
        }

        if (allSignatures.isEmpty()) {
            return formatNoResultFound(params);
        }

        return LspJsonFormatter.toJson(allSignatures);
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
