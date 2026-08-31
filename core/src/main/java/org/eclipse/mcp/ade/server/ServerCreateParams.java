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
package org.eclipse.mcp.ade.server;

import org.eclipse.mcp.ade.workspace.Workspace;

/**
 * Parameters for creating a server instance.
 * Base class for LSP and DAP server creation parameters.
 *
 * @param <C> Config type (LspServerConfig or DapServerConfig)
 */
public class ServerCreateParams<C extends ServerConfigBase> {

    private final C config;
    private final Workspace workspace;

    public ServerCreateParams(C config, Workspace workspace) {
        this.config = config;
        this.workspace = workspace;
    }

    public C getConfig() {
        return config;
    }

    public Workspace getWorkspace() {
        return workspace;
    }
}
