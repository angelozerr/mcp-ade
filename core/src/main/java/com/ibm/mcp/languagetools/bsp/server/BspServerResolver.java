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
package com.ibm.mcp.languagetools.bsp.server;

import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerResolverBase;
import com.ibm.mcp.languagetools.tools.ToolException;
import com.ibm.mcp.languagetools.workspace.Workspace;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.CompletableFuture;

/**
 * Resolves BSP servers for a given workspace.
 * Centralizes the logic for finding and starting appropriate build servers.
 */
@ApplicationScoped
public class BspServerResolver extends ServerResolverBase {

    /**
     * Find and ensure a BSP server is ready for the given workspace path.
     *
     * @param cwd the workspace root path
     * @return a future completing with the ready BSP server instance
     */
    public CompletableFuture<BspServer> getBspServerForWorkspace(String cwd) {
        Workspace workspace = resolveWorkspace(cwd);
        if (workspace == null) {
            return CompletableFuture.failedFuture(new ToolException("No workspace found for: " + cwd));
        }

        for (BspServerConfig config : application.getBspServerConfigs()) {
            if (!isEnabled(config)) {
                continue;
            }
            if (config.canHandle(workspace.getRootUri().toASCIIString(), workspace.getRootPath())) {
                return workspace.ensureBspServerReady(config.getServerId(), ProgressMonitor.none());
            }
        }

        return CompletableFuture.failedFuture(new ToolException("No BSP build server available for workspace: " + cwd));
    }
}
