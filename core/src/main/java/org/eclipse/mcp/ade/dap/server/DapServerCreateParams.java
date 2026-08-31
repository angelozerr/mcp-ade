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
package org.eclipse.mcp.ade.dap.server;

import org.eclipse.mcp.ade.dap.session.DapSession;
import org.eclipse.mcp.ade.server.ServerCreateParams;
import org.eclipse.mcp.ade.workspace.Workspace;

/**
 * Parameters for creating a DAP server instance.
 * Extends base parameters with DAP-specific session reference.
 */
public class DapServerCreateParams extends ServerCreateParams<DapServerConfig> {

    private final DapSession session;

    public DapServerCreateParams(DapSession session, DapServerConfig config, Workspace workspace) {
        super(config, workspace);
        this.session = session;
    }

    public DapSession getSession() {
        return session;
    }
}
