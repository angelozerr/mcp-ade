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

import com.ibm.mcp.languagetools.workspace.Workspace;
import org.jboss.logging.Logger;

/**
 * Default fallback factory for creating BspServer instances.
 * This factory is NOT registered via SPI - it's used as a last resort
 * when no other factory can handle the configuration.
 */
public class DefaultBspServerFactory implements BspServerFactory {

    private static final Logger LOG = Logger.getLogger(DefaultBspServerFactory.class);

    @Override
    public boolean canHandle(BspServerConfig config, Workspace workspace) {
        // This factory handles everything as a fallback
        return true;
    }

    @Override
    public BspServer createServer(BspServerCreateParams params) {
        LOG.infof("Creating default BSP server for %s", params.getConfig().getServerId());
        return new BspServer(params.getConfig(), params.getWorkspace());
    }
}
