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
package org.eclipse.mcp.ade.lsp.tools;

import org.eclipse.mcp.ade.language.LanguageDocument;
import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerResolver;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import org.eclipse.mcp.ade.tools.ToolException;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@ApplicationScoped
public class DocumentTools {

    @Inject
    LspServerResolver serverResolver;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(description = "Open a document in language servers to keep it active for multiple LSP operations (references, definition, rename...). "
            + "The file stays open until you call close_document. "
            + "If you don't call open_document, LSP tools will auto-open and auto-close the file for each request. "
            + "Use this when you plan to execute several LSP features on the same file to avoid repeated open/close cycles.")
    public CompletableFuture<String> open_document(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri) {
        LanguageDocument document = languageRegistry.createDocument(uri);
        String languageId = languageRegistry.detectLanguage(URI.create(uri)).orElse("");
        return serverResolver.getLspServersForFile(
                        document, cwd, LspServer::isEnabled, ProgressMonitor.none())
                .thenCompose(servers -> waitForReadyAndOpen(servers, uri, languageId));
    }

    @Tool(description = "Close a document previously opened with open_document. "
            + "Always close documents when you're done with multiple LSP operations on a file.")
    public CompletableFuture<String> close_document(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri) {
        LanguageDocument document = languageRegistry.createDocument(uri);
        return serverResolver.getLspServersForFile(
                        document, cwd, LspServer::isEnabled, ProgressMonitor.none())
                .thenApply(servers -> {
                    List<String> closedIn = servers.stream()
                            .filter(server -> server.isExplicitlyOpened(uri))
                            .peek(server -> server.closeFileExplicitly(uri))
                            .map(server -> server.getConfig().getName())
                            .toList();
                    if (closedIn.isEmpty()) {
                        return "Document was not open: " + uri;
                    }
                    return String.format("Closed %s in: %s", uri, String.join(", ", closedIn));
                });
    }

    private CompletableFuture<String> waitForReadyAndOpen(List<LspServer> servers, String fileUri, String languageId) {
        if (servers.isEmpty()) {
            throw new ToolException("No language server found for: " + fileUri);
        }
        List<CompletableFuture<String>> futures = servers.stream()
                .map(server -> server.waitForReady()
                        .thenApply(v -> {
                            server.openFileExplicitly(fileUri, languageId);
                            return server.getConfig().getName();
                        }))
                .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    String serverNames = futures.stream()
                            .map(CompletableFuture::join)
                            .collect(Collectors.joining(", "));
                    return String.format("Opened %s in: %s", fileUri, serverNames);
                });
    }
}
