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

import java.util.Objects;

/**
 * Base SPI interface for creating server implementations (LSP or DAP).
 * Extensions implement this interface and register via ServiceLoader.
 *
 * @param <C> Config type (extends ServerConfigBase)
 * @param <S> Server type (extends ServerBase)
 * @param <P> Params type (extends ServerCreateParams)
 */
public interface ServerFactory<C extends ServerConfigBase, S extends ServerBase<C>, P extends ServerCreateParams<C>> {

    /**
     * Get the server ID that this factory handles (e.g., "jdtls", "java-debug").
     * Returns null if this factory uses canHandle() logic instead of a specific serverId.
     */
    default String getServerId() {
        return null;
    }

    /**
     * Check if this factory can handle the given configuration.
     * Default implementation checks if serverId matches.
     *
     * @param config The server configuration to check
     * @param workspace The workspace (for factories that need to check other configs)
     * @return true if this factory can handle the config
     */
    default boolean canHandle(C config, Workspace workspace) {
        return config != null && Objects.equals(getServerId(), config.getServerId());
    }

    /**
     * Create a server instance.
     *
     * @param params Server creation parameters
     * @return A new server instance
     */
    S createServer(P params);
}
