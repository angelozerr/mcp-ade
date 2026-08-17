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
package com.ibm.mcp.languagetools.extensions.jdtls.build;

import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.progress.AbstractProgressMonitor;
import com.ibm.mcp.languagetools.trace.TraceCollector;

import java.util.Objects;

import java.util.concurrent.CompletableFuture;

/**
 * Progress monitor that bridges to {@link LspServer#setStatusMessage(String)}
 * and optionally traces messages via {@link TraceCollector}.
 *
 * <p>Used during build support classpath extraction to make progress
 * visible in the admin UI and in server traces.</p>
 */
public class ServerStatusProgressMonitor extends AbstractProgressMonitor {

    private final LspServer server;
    private final TraceCollector traceCollector;
    private final String workspaceUri;

    public ServerStatusProgressMonitor(LspServer server) {
        super(100.0);
        this.server = Objects.requireNonNull(server, "server");
        this.traceCollector = server.getTraceCollector();
        this.workspaceUri = server.getWorkspace() != null
                ? server.getWorkspace().getNormalizedUri() : null;
    }

    @Override
    public void reportProgress(String message) {
        server.setStatusMessage(message);
        if (traceCollector != null && traceCollector.isEnabled()) {
            traceCollector.addTrace(workspaceUri, server.getId(),
                    message, TraceCollector.MessageType.INFO);
        }
    }

    @Override
    public void reportProgress(double progress, String message) {
        reportProgress(message);
    }

    @Override
    public void reportTrace(String message) {
        if (traceCollector != null && traceCollector.isEnabled()) {
            traceCollector.addTrace(workspaceUri, server.getId(),
                    message, TraceCollector.MessageType.INFO);
        }
    }

    @Override
    public void setComplete() {
        server.setStatusMessage(null);
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public void checkCancelled() {
    }

    @Override
    public <T> CompletableFuture<T> executeWithCancellation(CompletableFuture<T> future) {
        return future;
    }

    @Override
    public boolean isSupported() {
        return true;
    }
}
