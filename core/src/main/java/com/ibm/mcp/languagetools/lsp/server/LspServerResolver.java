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
package com.ibm.mcp.languagetools.lsp.server;

import com.ibm.mcp.languagetools.language.LanguageDocument;
import com.ibm.mcp.languagetools.operation.OperationContext;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerResolverBase;
import com.ibm.mcp.languagetools.workspace.Workspace;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Resolves LSP servers for a given file with optional filtering.
 * Centralizes the logic for finding appropriate language servers.
 */
@ApplicationScoped
public class LspServerResolver extends ServerResolverBase {

    public CompletableFuture<List<LspServer>> getLspServersForFile(
            LanguageDocument document,
            String cwd,
            Predicate<LspServer> filter,
            ProgressMonitor progressMonitor) {
        return getLspServersForFile(document, cwd, filter, progressMonitor, OperationContext.noop());
    }

    public CompletableFuture<List<LspServer>> getLspServersForFile(
            LanguageDocument document,
            String cwd,
            Predicate<LspServer> filter,
            ProgressMonitor progressMonitor,
            OperationContext operationContext) {

        Workspace workspace = resolveWorkspace(cwd);
        return application.ensureServersForFile(document.getUri(), workspace, progressMonitor, operationContext)
                .thenApply(v -> {
                    var allServers = workspace.getLspServers();
                    URI fileUri = document.getUri();
                    String languageId = document.getLanguageId();
                    java.nio.file.Path basePath = workspace.getRootPath();
                    return allServers
                            .stream()
                            .filter(server -> server.getConfig().canHandle(fileUri, languageId, basePath))
                            .filter(filter)
                            .toList();
                });
    }

    /**
     * Get all LSP servers for a workspace (without specific file).
     * Used for workspace-level operations like workspace/symbol.
     */
    public CompletableFuture<List<LspServer>> getLspServersForWorkspace(
            String cwd,
            Predicate<LspServer> filter) {

        Workspace workspace = resolveWorkspace(cwd);
        return application.ensureServersForWorkspace(workspace, ProgressMonitor.none())
                .thenApply(v -> workspace.getLspServers()
                        .stream()
                        .filter(filter)
                        .toList()
                );
    }
}
