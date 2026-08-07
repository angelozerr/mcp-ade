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
package com.ibm.mcp.languagetools.lsp.tools;

import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.language.LanguageRegistry;
import com.ibm.mcp.languagetools.lsp.tools.params.FileUriRequestParams;
import com.ibm.mcp.languagetools.lsp.tools.strategies.DiagnosticsStrategy;
import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
import com.ibm.mcp.languagetools.workspace.Workspace;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.Diagnostic;
import org.jboss.logging.Logger;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class DiagnosticsTools {

    private static final Logger LOG = Logger.getLogger(DiagnosticsTools.class);

    @Inject
    Application application;

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(
            name = "get_diagnostics",
            description = "Get diagnostics (errors, warnings) for a file from all language servers. " +
            "The workspace is auto-detected and initialized if needed. " +
            "Example: getDiagnostics(cwd='/home/user/projects/my-app', fileUri='file:///home/user/projects/my-app/src/Main.java')")
    public CompletableFuture<String> getDiagnostics(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            Cancellation cancellation,
            Progress progress) {
        FileUriRequestParams params = new FileUriRequestParams(cwd, uri);
        return requestExecutor.execute(params, new DiagnosticsStrategy(languageRegistry), cancellation, progress);
    }

    @Tool(
            name="get_all_diagnostics",
            description = "Get all diagnostics from all files in a workspace. " +
            "The workspace is auto-detected and initialized if needed. " +
            "Example: get_all_diagnostics(cwd='/home/user/projects/my-app')")
    public String getAllDiagnostics(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd) {
        try {
            if (cwd == null || cwd.isEmpty()) {
                return "Error: cwd must be provided";
            }

            URI uri = new File(cwd).toURI();
            LOG.infof("Getting all diagnostics for workspace: %s", uri);

            Workspace ws = application.getOrCreateWorkspace(uri);

            StringBuilder result = new StringBuilder();
            result.append("All diagnostics for workspace: ").append(uri).append("\n\n");

            var servers = ws.getLspServers();
            if (servers.isEmpty()) {
                return "No language servers available in workspace";
            }

            boolean foundDiagnostics = false;

            for (var server : servers) {
                String serverId = server.getId();

                Map<String, List<Diagnostic>> allDiagnostics = server.getDiagnosticsCache();

                if (!allDiagnostics.isEmpty()) {
                    foundDiagnostics = true;
                    result.append(String.format("Language Server: %s (%s)\n",
                            serverId, server.getConfig().getName()));

                    for (Map.Entry<String, List<Diagnostic>> fileEntry : allDiagnostics.entrySet()) {
                        String fileUri = fileEntry.getKey();
                        List<Diagnostic> diagnostics = fileEntry.getValue();

                        if (!diagnostics.isEmpty()) {
                            result.append(String.format("\n  File: %s\n", fileUri));
                            for (Diagnostic diagnostic : diagnostics) {
                                result.append(String.format("    [%s] Line %d: %s\n",
                                        diagnostic.getSeverity(),
                                        diagnostic.getRange().getStart().getLine() + 1,
                                        diagnostic.getMessage()));
                            }
                        }
                    }
                    result.append("\n");
                }
            }

            if (!foundDiagnostics) {
                return "No diagnostics found in workspace";
            }

            return result.toString();

        } catch (Exception e) {
            LOG.error("Failed to get all diagnostics", e);
            return "Failed to get all diagnostics: " + e.getMessage();
        }
    }

}
