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
import org.eclipse.mcp.ade.server.ServerFactoryRegistryBase;
import org.eclipse.mcp.ade.workspace.Workspace;
import org.jboss.logging.Logger;

/**
 * Registry for DAP server factories.
 * Discovers and manages all available DapServerFactory implementations via Java SPI (ServiceLoader).
 * Factories are selected based on canHandle() method and results are cached.
 */
public class DapServerFactoryRegistry extends ServerFactoryRegistryBase<DapServerConfig, DapServer, DapServerCreateParams, DapServerFactory> {

    private static final Logger LOG = Logger.getLogger(DapServerFactoryRegistry.class);
    private static final DapServerFactoryRegistry INSTANCE = new DapServerFactoryRegistry();

    private final DapServerFactory defaultFactory = new DefaultDapServerFactory();

    private DapServerFactoryRegistry() {
        super(DapServerFactory.class);
    }

    public static DapServerFactoryRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Create a DAP server instance based on the config.
     * Convenience method that wraps session, config and workspace in params.
     *
     * @param session The DAP session
     * @param config The DAP server configuration
     * @param workspace The workspace
     * @return The created DAP server (never null - falls back to default)
     */
    public DapServer createServer(DapSession session, DapServerConfig config, Workspace workspace) {
        return createServer(new DapServerCreateParams(session, config, workspace));
    }

    @Override
    protected DapServerFactory getDefaultFactory() {
        return defaultFactory;
    }

}
