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
import com.ibm.mcp.languagetools.tools.ToolException;
import com.ibm.mcp.languagetools.utils.UriUtils;
import com.ibm.mcp.languagetools.workspace.Workspace;
import com.google.gson.Gson;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.Diagnostic;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;
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

    private static final Gson GSON = new Gson();

    @Tool(
            name="get_all_diagnostics",
            description = "Get all diagnostics from all files in a workspace. " +
            "The workspace is auto-detected and initialized if needed. " +
            "Example: get_all_diagnostics(cwd='/home/user/projects/my-app')")
    public String getAllDiagnostics(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd) {
        try {
            if (cwd == null || cwd.isEmpty()) {
                throw new ToolException("cwd must be provided");
            }

            URI uri = UriUtils.toUri(cwd);
            LOG.infof("Getting all diagnostics for workspace: %s", uri);

            Workspace ws = application.getOrCreateWorkspace(uri);

            var servers = ws.getLspServers();
            if (servers.isEmpty()) {
                throw new ToolException("No language servers available in workspace");
            }

            String cwdUri = UriUtils.cwdToUriPrefix(cwd);
            List<Map<String, Object>> files = new ArrayList<>();

            for (var server : servers) {
                Map<String, List<Diagnostic>> allDiagnostics = server.getDiagnosticsCache();
                for (Map.Entry<String, List<Diagnostic>> fileEntry : allDiagnostics.entrySet()) {
                    List<Diagnostic> diagnostics = fileEntry.getValue();
                    if (!diagnostics.isEmpty()) {
                        String compactFile = UriUtils.compactUri(fileEntry.getKey(), cwdUri);
                        List<Map<String, Object>> diags = new ArrayList<>();
                        for (Diagnostic d : diagnostics) {
                            Map<String, Object> diag = new LinkedHashMap<>();
                            diag.put("range", (d.getRange().getStart().getLine() + 1) + ":" + d.getRange().getStart().getCharacter()
                                    + "-" + (d.getRange().getEnd().getLine() + 1) + ":" + d.getRange().getEnd().getCharacter());
                            if (d.getSeverity() != null) {
                                diag.put("severity", d.getSeverity().name());
                            }
                            diag.put("message", d.getMessage());
                            diags.add(diag);
                        }
                        Map<String, Object> fileInfo = new LinkedHashMap<>();
                        fileInfo.put("file", compactFile);
                        fileInfo.put("diagnostics", diags);
                        files.add(fileInfo);
                    }
                }
            }

            if (files.isEmpty()) {
                return "[]";
            }
            return GSON.toJson(files);

        } catch (Exception e) {
            LOG.error("Failed to get all diagnostics", e);
            throw new ToolException("Failed to get all diagnostics: " + e.getMessage(), e);
        }
    }

}
