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
package org.eclipse.mcp.ade.extensions.microprofile.lsp;

import org.eclipse.mcp.ade.lsp.server.ClasspathExtensibleLspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.eclipse.mcp.ade.utils.JsonUtils;
import org.eclipse.mcp.ade.workspace.Workspace;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Custom LSP server for MicroProfile LS.
 * Uses delegate command handlers registered in JDT.LS by the lsp4mp JDT extension
 * (MicroProfileDelegateCommandHandlerForJava) for Java files.
 * Non-Java files (e.g. application.properties) use standard didOpen + publishDiagnostics.
 */
public class MicroProfileLspServer extends ClasspathExtensibleLspServer {

    private static final Logger LOG = Logger.getLogger(MicroProfileLspServer.class);

    private static final String JAVA_LANGUAGE_ID = "java";

    // Delegate command handlers for Java files
    private static final String DIAGNOSTICS_COMMAND = "microprofile/java/diagnostics";
    private static final String CODEACTION_COMMAND = "microprofile/java/codeAction";

    public MicroProfileLspServer(LspServerConfig config, Workspace workspace) {
        super(config, workspace);
    }

    /**
     * Get diagnostics for MicroProfile.
     * <ul>
     *   <li>Java files: uses "microprofile/java/diagnostics" delegate command handler
     *       (params: {@code {"uris": ["file:///..."]}} → returns {@code List<PublishDiagnosticsParams>})</li>
     *   <li>Non-Java files (e.g. application.properties): didOpen + publishDiagnostics</li>
     * </ul>
     */
    @Override
    protected CompletableFuture<List<Diagnostic>> doGetDiagnostics(String uri, String languageId, boolean autoClose) {
        if (!JAVA_LANGUAGE_ID.equals(languageId)) {
            return super.doGetDiagnostics(uri, languageId, autoClose);
        }
        List<Diagnostic> cached = getDiagnosticsCache().get(uri);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        var client = getLanguageClient();
        if (client == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        Object params = Map.of("uris", List.of(uri));
        return client.request(DIAGNOSTICS_COMMAND, params)
                .thenApply(result -> {
                    List<Diagnostic> diags = parseDiagnosticsResult(result);
                    getDiagnosticsCache().put(uri, diags);
                    return diags;
                })
                .exceptionally(ex -> {
                    LOG.warnf(ex, "Diagnostics request '%s' failed for %s", DIAGNOSTICS_COMMAND, uri);
                    return Collections.emptyList();
                });
    }

    /**
     * Get code actions for MicroProfile.
     * <ul>
     *   <li>Java files: uses "microprofile/java/codeAction" delegate command handler
     *       (params: MicroProfileJavaCodeActionParams with textDocument, range, context + booleans,
     *        returns {@code List<? extends CodeAction>})</li>
     *   <li>Non-Java files: standard textDocument/codeAction</li>
     * </ul>
     * <p>
     * NOTE: currently fails due to lsp4mp serialization bug — the delegate handler returns
     * CodeAction with TextEdit instead of Either&lt;TextEdit, SnippetTextEdit&gt;,
     * causing ClassCastException in JDT.LS EitherTypeAdapter. Needs fix in lsp4mp.
     */
    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> getCodeActions(CodeActionParams params, String languageId) {
        if (!JAVA_LANGUAGE_ID.equals(languageId)) {
            // Non-Java files: standard textDocument/codeAction
            return super.getCodeActions(params, languageId);
        }
        // Java files: "microprofile/java/codeAction" delegate command handler
        var client = getLanguageClient();
        if (client == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        Map<String, Object> mpParams = new HashMap<>();
        mpParams.put("textDocument", params.getTextDocument());
        mpParams.put("range", params.getRange());
        mpParams.put("context", params.getContext());
        mpParams.put("resourceOperationSupported", true);
        mpParams.put("commandConfigurationUpdateSupported", true);
        mpParams.put("resolveSupported", false);

        return client.request(CODEACTION_COMMAND, mpParams)
                .thenApply(this::parseCodeActionResult)
                .exceptionally(ex -> {
                    LOG.warnf(ex, "Code action request '%s' failed", CODEACTION_COMMAND);
                    return Collections.emptyList();
                });
    }

    private List<Diagnostic> parseDiagnosticsResult(Object result) {
        if (result instanceof List<?> list) {
            return list.stream()
                    .map(item -> JsonUtils.toModel(item, PublishDiagnosticsParams.class))
                    .filter(Objects::nonNull)
                    .filter(pdp -> pdp.getDiagnostics() != null)
                    .flatMap(pdp -> pdp.getDiagnostics().stream())
                    .toList();
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<Either<Command, CodeAction>> parseCodeActionResult(Object result) {
        if (result instanceof List<?> list) {
            return list.stream()
                    .map(item -> JsonUtils.toModel(item, CodeAction.class))
                    .filter(Objects::nonNull)
                    .map(action -> (Either<Command, CodeAction>) Either.<Command, CodeAction>forRight(action))
                    .toList();
        }
        return Collections.emptyList();
    }
}
