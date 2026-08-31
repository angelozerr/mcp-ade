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
package org.eclipse.mcp.ade.bsp.server;

import org.eclipse.mcp.ade.server.ServerCreateParams;
import org.eclipse.mcp.ade.workspace.Workspace;

/**
 * Parameters for creating a BSP server instance.
 */
public class BspServerCreateParams extends ServerCreateParams<BspServerConfig> {

    public BspServerCreateParams(BspServerConfig config, Workspace workspace) {
        super(config, workspace);
    }
}
