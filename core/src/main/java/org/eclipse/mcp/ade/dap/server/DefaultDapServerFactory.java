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

import org.eclipse.mcp.ade.workspace.Workspace;
import org.jboss.logging.Logger;

/**
 * Default fallback factory for creating DapServer instances.
 * This factory is NOT registered via SPI - it's used as a last resort
 * when no other factory can handle the configuration.
 */
public class DefaultDapServerFactory implements DapServerFactory {

    private static final Logger LOG = Logger.getLogger(DefaultDapServerFactory.class);

    public boolean canHandle(DapServerConfig config, Workspace workspace) {
        // This factory handles everything as a fallback
        return true;
    }

    @Override
    public DapServer createServer(DapServerCreateParams params) {
        LOG.infof("Creating default DAP server for %s (session: %s)", params.getConfig().getServerId(), params.getSession().getSessionId());
        return new DapServer(params.getSession(), params.getConfig(), params.getWorkspace());
    }
}
