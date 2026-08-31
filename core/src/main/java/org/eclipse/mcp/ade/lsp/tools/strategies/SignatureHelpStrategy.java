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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/signatureHelp requests.
 */
public class SignatureHelpStrategy extends FilePositionBasedStrategy<SignatureHelpParams, SignatureHelp> {

    public SignatureHelpStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry, LspCapability.SIGNATURE_HELP, "Signature help", SignatureHelpParams::new);
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
