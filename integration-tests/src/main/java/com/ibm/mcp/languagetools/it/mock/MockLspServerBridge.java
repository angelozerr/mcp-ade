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
package com.ibm.mcp.languagetools.it.mock;

import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * Bridge between the MCP LSP framework and the in-process {@link MockLspLanguageServer}.
 * <p>
 * Overrides {@link LspServer#launchProcess()} to create an in-process connection
 * via piped streams instead of launching an external OS process.
 */
public class MockLspServerBridge extends LspServer {

    public MockLspServerBridge(LspServerConfig config, Workspace workspace) {
        super(config, workspace);
    }

    @Override
    protected void launchProcess() throws IOException {
        MockLspLanguageServer mockServer = new MockLspLanguageServer();

        // Create piped streams for bidirectional communication
        PipedInputStream clientToServerIn = new PipedInputStream();
        PipedOutputStream clientToServerOut = new PipedOutputStream(clientToServerIn);
        PipedInputStream serverToClientIn = new PipedInputStream();
        PipedOutputStream serverToClientOut = new PipedOutputStream(serverToClientIn);

        // Server-side launcher: reads from clientToServerIn, writes to serverToClientOut
        Launcher<LanguageClient> serverLauncher = LSPLauncher.createServerLauncher(
                mockServer, clientToServerIn, serverToClientOut);
        mockServer.connect(serverLauncher.getRemoteProxy());
        serverLauncher.startListening();

        // Client-side launcher: reads from serverToClientIn, writes to clientToServerOut
        Launcher<LanguageServer> clientLauncher = createLauncher(serverToClientIn, clientToServerOut);
        setLanguageServer(clientLauncher.getRemoteProxy());
        setListeningFuture(clientLauncher.startListening());
    }

    @Override
    protected CompletableFuture<Void> ensureContributorsInstalled(ProgressMonitor progressMonitor) {
        // No contributors to install for the mock server
        return CompletableFuture.completedFuture(null);
    }
}
