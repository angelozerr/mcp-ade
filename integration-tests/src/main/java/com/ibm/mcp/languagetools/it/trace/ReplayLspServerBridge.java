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
package com.ibm.mcp.languagetools.it.trace;

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
 * Bridge between the MCP LSP framework and the in-process {@link ReplayLspLanguageServer}.
 * <p>
 * Overrides {@link LspServer#launchProcess()} to create an in-process connection
 * via piped streams, connecting a {@link ReplayLspLanguageServer} that replays
 * responses from recorded LSP trace data.
 */
public class ReplayLspServerBridge extends LspServer {

    private final LspTraceData traceData;

    /**
     * Create a new replay LSP server bridge.
     *
     * @param config    the LSP server configuration
     * @param workspace the workspace this server is associated with
     * @param traceData the parsed LSP trace data for replaying responses
     */
    public ReplayLspServerBridge(LspServerConfig config, Workspace workspace, LspTraceData traceData) {
        super(config, workspace);
        this.traceData = traceData;
    }

    /**
     * Launch the replay LSP server in-process using piped streams.
     * <p>
     * Creates two pairs of {@link PipedInputStream}/{@link PipedOutputStream}
     * for bidirectional communication:
     * <ul>
     *   <li>Client to Server: client writes to {@code clientToServerOut}, server reads from {@code clientToServerIn}</li>
     *   <li>Server to Client: server writes to {@code serverToClientOut}, client reads from {@code serverToClientIn}</li>
     * </ul>
     *
     * @throws IOException if the piped streams cannot be created
     */
    @Override
    protected void launchProcess() throws IOException {
        ReplayLspLanguageServer replayServer = new ReplayLspLanguageServer(traceData);

        PipedInputStream clientToServerIn = new PipedInputStream();
        PipedOutputStream clientToServerOut = new PipedOutputStream(clientToServerIn);
        PipedInputStream serverToClientIn = new PipedInputStream();
        PipedOutputStream serverToClientOut = new PipedOutputStream(serverToClientIn);

        // Server-side launcher: reads from clientToServerIn, writes to serverToClientOut
        Launcher<LanguageClient> serverLauncher = LSPLauncher.createServerLauncher(
                replayServer, clientToServerIn, serverToClientOut);
        replayServer.connect(serverLauncher.getRemoteProxy());
        serverLauncher.startListening();

        // Client-side launcher: reads from serverToClientIn, writes to clientToServerOut
        Launcher<LanguageServer> clientLauncher = createLauncher(serverToClientIn, clientToServerOut);
        setLanguageServer(clientLauncher.getRemoteProxy());
        setListeningFuture(clientLauncher.startListening());
    }

    /**
     * Skip contributor installation — the replay server has no external dependencies.
     *
     * @param progressMonitor the progress monitor (unused)
     * @return a completed future
     */
    @Override
    protected CompletableFuture<Void> ensureContributorsInstalled(ProgressMonitor progressMonitor) {
        return CompletableFuture.completedFuture(null);
    }
}
