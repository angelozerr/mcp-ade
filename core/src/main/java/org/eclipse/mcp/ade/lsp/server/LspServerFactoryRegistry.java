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
package org.eclipse.mcp.ade.lsp.server;

import org.eclipse.mcp.ade.server.ServerFactoryRegistryBase;
import org.eclipse.mcp.ade.workspace.Workspace;
import org.jboss.logging.Logger;

/**
 * Registry for LSP server factories.
 * Uses SPI (ServiceLoader) to discover custom server factory implementations.
 * Factories are selected based on canHandle() method and results are cached.
 */
public class LspServerFactoryRegistry extends ServerFactoryRegistryBase<LspServerConfig, LspServer, LspServerCreateParams, LspServerFactory> {

    private static final Logger LOG = Logger.getLogger(LspServerFactoryRegistry.class);
    private static final LspServerFactoryRegistry INSTANCE = new LspServerFactoryRegistry();

    private final LspServerFactory defaultFactory = new DefaultLspServerFactory();

    private LspServerFactoryRegistry() {
        super(LspServerFactory.class);
    }

    public static LspServerFactoryRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Create an LSP server instance based on the config.
     * Convenience method that wraps config and workspace in params.
     */
    public LspServer createServer(LspServerConfig config, Workspace workspace) {
        return createServer(new LspServerCreateParams(config, workspace));
    }

    @Override
    protected LspServerFactory getDefaultFactory() {
        return defaultFactory;
    }
}
