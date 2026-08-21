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
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for LSP textDocument/definition requests.
 */
public class DefinitionStrategy extends LocationBasedStrategy<DefinitionParams> {

    public DefinitionStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry, LspCapability.DEFINITION, "Go to definition", DefinitionParams::new);
    }

    @Override
    protected CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> doExecuteRequest(
            LspServer server, DefinitionParams lspParams) {
        return server.getLanguageServer()
                .getTextDocumentService()
                .definition(lspParams);
    }
}
