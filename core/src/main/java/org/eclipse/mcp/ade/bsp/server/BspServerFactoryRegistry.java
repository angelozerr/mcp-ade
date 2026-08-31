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

import org.eclipse.mcp.ade.server.ServerFactoryRegistryBase;
import org.eclipse.mcp.ade.workspace.Workspace;
import org.jboss.logging.Logger;

/**
 * Registry for BSP server factories.
 * Uses SPI (ServiceLoader) to discover custom server factory implementations.
 * Factories are selected based on canHandle() method and results are cached.
 */
public class BspServerFactoryRegistry extends ServerFactoryRegistryBase<BspServerConfig, BspServer, BspServerCreateParams, BspServerFactory> {

    private static final Logger LOG = Logger.getLogger(BspServerFactoryRegistry.class);
    private static final BspServerFactoryRegistry INSTANCE = new BspServerFactoryRegistry();

    private final BspServerFactory defaultFactory = new DefaultBspServerFactory();

    private BspServerFactoryRegistry() {
        super(BspServerFactory.class);
    }

    public static BspServerFactoryRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Create a BSP server instance based on the config.
     * Convenience method that wraps config and workspace in params.
     */
    public BspServer createServer(BspServerConfig config, Workspace workspace) {
        return createServer(new BspServerCreateParams(config, workspace));
    }

    @Override
    protected BspServerFactory getDefaultFactory() {
        return defaultFactory;
    }
}
