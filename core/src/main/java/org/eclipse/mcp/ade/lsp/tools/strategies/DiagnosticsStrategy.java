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

import org.eclipse.mcp.ade.language.LanguageDocument;
import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.client.LspCapability;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerResolver;
import org.eclipse.mcp.ade.lsp.tools.params.FileUriRequestParams;
import org.eclipse.mcp.ade.operation.OperationContext;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.utils.UriUtils;
import org.eclipse.lsp4j.Diagnostic;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DiagnosticsStrategy
        extends DidOpenBasedStrategy<FileUriRequestParams, String, List<Diagnostic>> {

    public DiagnosticsStrategy(LanguageRegistry languageRegistry) {
        super(languageRegistry);
    }

    @Override
    public LspCapability getCapability() {
        return LspCapability.DIAGNOSTIC;
    }

    @Override
    public String getTitle() {
        return "Diagnostics";
    }

    @Override
    public CompletableFuture<List<LspServer>> resolveServers(
            LspServerResolver resolver,
            FileUriRequestParams params,
            ProgressMonitor progressMonitor,
            OperationContext operationContext) {
        LanguageDocument document = languageRegistry.createDocument(params.getFileUri());
        return resolver.getLspServersForFile(
                document,
                params.getCwd(),
                LspServer::isEnabled,
                progressMonitor,
                operationContext
        );
    }

    @Override
    public String buildLspParams(FileUriRequestParams params) {
        return params.getFileUri();
    }

    @Override
    protected String extractFileUri(String lspParams) {
        return lspParams;
    }

    @Override
    protected CompletableFuture<List<Diagnostic>> executeAfterDiagnostics(LspServer server, String lspParams) {
        List<Diagnostic> cached = server.getDiagnosticsCache().get(UriUtils.normalizeUri(lspParams));
        return CompletableFuture.completedFuture(cached != null ? cached : Collections.emptyList());
    }

    @Override
    public List<Diagnostic> getEmptyResult() {
        return Collections.emptyList();
    }

    @Override
    public boolean isValidResult(List<Diagnostic> result) {
        return result != null && !result.isEmpty();
    }

    @Override
    public String formatResults(FileUriRequestParams params, List<List<Diagnostic>> results) {
        List<Diagnostic> all = results.stream().flatMap(List::stream).toList();
        if (all.isEmpty()) {
            return formatNoResultFound(params);
        }
        return LspJsonFormatter.toJson(all.stream().map(LspJsonFormatter::diagnostic).toList());
    }

    @Override
    public String formatNoResultFound(FileUriRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
