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
package org.eclipse.mcp.ade.extensions.dotnet.lsp;

import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.utils.UriUtils;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.eclipse.mcp.ade.workspace.Workspace;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Roslyn language server with custom language client for settings conversion
 * and project/solution open notifications.
 */
public class RoslynLspServer extends LspServer {

    private static final Logger LOG = Logger.getLogger(RoslynLspServer.class);

    public RoslynLspServer(LspServerConfig config, Workspace workspace) {
        super(config, workspace);
    }

    @Override
    protected LanguageClient createLanguageClient() {
        return new RoslynLanguageClient(this);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return super.initialize()
                .thenRun(this::openProjectsOrSolution);
    }

    private void openProjectsOrSolution() {
        var ls = getLanguageServer();
        if (!(ls instanceof Endpoint endpoint)) {
            return;
        }

        Path workspacePath = Paths.get(getWorkspace().getRootUri());

        // Try .sln first
        List<Path> slnFiles = findFiles(workspacePath, "*.sln");
        if (!slnFiles.isEmpty()) {
            URI slnUri = slnFiles.get(0).toUri();
            Map<String, Object> params = new HashMap<>();
            params.put("solution", UriUtils.toFileUriString(slnUri));
            LOG.infof("[roslyn] Sending solution/open: %s", slnUri);
            endpoint.notify("solution/open", params);
            return;
        }

        // No .sln — try .csproj files
        List<Path> csprojFiles = findFiles(workspacePath, "*.csproj");
        if (!csprojFiles.isEmpty()) {
            List<String> projectUris = new ArrayList<>();
            for (Path csproj : csprojFiles) {
                projectUris.add(UriUtils.toFileUriString(csproj.toUri()));
            }
            Map<String, Object> params = new HashMap<>();
            params.put("projects", projectUris);
            LOG.infof("[roslyn] Sending project/open for %d project(s): %s", csprojFiles.size(), projectUris);
            endpoint.notify("project/open", params);
        }
    }

    private static List<Path> findFiles(Path dir, String glob) {
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path entry : stream) {
                result.add(entry);
            }
        } catch (IOException e) {
            // Ignore — directory may not be readable
        }
        return result;
    }
}
