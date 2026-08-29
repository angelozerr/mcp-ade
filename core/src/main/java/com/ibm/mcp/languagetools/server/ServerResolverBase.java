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
package com.ibm.mcp.languagetools.server;

import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.workspace.Workspace;
import jakarta.inject.Inject;

/**
 * Base class for server resolvers (LSP, DAP, BSP).
 * Provides common workspace resolution and enabled-state checking.
 */
public abstract class ServerResolverBase {

    @Inject
    protected Application application;

    protected Workspace resolveWorkspace(String cwd) {
        return application.getWorkspaceForPath(cwd);
    }

    public boolean isEnabled(ServerConfigBase config) {
        return application.getExtensionRegistry().isServerConfigEnabled(config);
    }
}
