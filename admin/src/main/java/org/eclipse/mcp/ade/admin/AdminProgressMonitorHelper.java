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
package org.eclipse.mcp.ade.admin;

import org.eclipse.mcp.ade.dap.session.DapSession;
import org.eclipse.mcp.ade.installer.TraceProgressMonitor;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.progress.ProgressBroadcaster;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Helper for creating ProgressMonitor instances in Admin UI endpoints.
 */
@ApplicationScoped
public class AdminProgressMonitorHelper {

    @Inject
    ProgressBroadcaster broadcaster;

    public ProgressMonitor forLspServer(LspServer server, String operation) {
        if (server == null) {
            return ProgressMonitor.none();
        }

        String taskId = operation + "-" + server.getId();
        String title = capitalize(operation) + " " + server.getId();

        return new TraceProgressMonitor(
            server.getTraceCollector(),
            100.0,
            broadcaster,
            taskId,
            server.getId(),
            title
        );
    }

    public ProgressMonitor forLspServer(LspServer server) {
        return forLspServer(server, "install");
    }

    public ProgressMonitor forDapSession(DapSession session, String operation) {
        if (session == null || session.getDapServer() == null) {
            return ProgressMonitor.none();
        }

        String taskId = operation + "-" + session.getSessionId();
        String title = capitalize(operation) + " " + session.getSessionId();

        return new TraceProgressMonitor(
            session.getDapServer().getTraceCollector(),
            100.0,
            broadcaster,
            taskId,
            session.getSessionId(),
            title
        );
    }

    public ProgressMonitor forDapSession(DapSession session) {
        return forDapSession(session, "launch");
    }

    private static String capitalize(String s) {
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
