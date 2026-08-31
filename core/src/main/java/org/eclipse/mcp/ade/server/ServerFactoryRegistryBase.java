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
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for server factory registries (LSP and DAP).
 * Handles common logic: SPI loading, sorting, caching, canHandle iteration.
 *
 * @param <C> Config type (LspServerConfig or DapServerConfig)
 * @param <S> Server type (LspServer or DapServer)
 * @param <P> Params type (LspServerCreateParams or DapServerCreateParams)
 * @param <F> Factory type (LspServerFactory or DapServerFactory)
 */
public abstract class ServerFactoryRegistryBase<C extends ServerConfigBase, S extends ServerBase<C>, P extends ServerCreateParams<C>, F extends ServerFactory<C, S, P>> {

    private static final Logger LOG = Logger.getLogger(ServerFactoryRegistryBase.class);

    protected final List<F> spiFactories = new ArrayList<>();
    protected final Map<String, F> factoryCache = new ConcurrentHashMap<>();

    protected ServerFactoryRegistryBase(Class<F> factoryClass) {

        // Load all factory implementations via SPI
        ServiceLoader<F> loader = ServiceLoader.load(factoryClass);
        for (F factory : loader) {
            spiFactories.add(factory);
            LOG.infof("Registered factory: %s", factory.getClass().getSimpleName());
        }

        // Sort factories: those with non-null getServerId() first (more specific)
        spiFactories.sort((f1, f2) -> {
            String id1 = f1.getServerId();
            String id2 = f2.getServerId();
            if (id1 != null && id2 == null) return -1;
            if (id1 == null && id2 != null) return 1;
            return 0;
        });
    }

    /**
     * Find the appropriate factory for the given config.
     * Iterates through factories calling canHandle(), uses cache, and falls back to default.
     */
    protected F findFactory(C config, Workspace workspace, String serverId) {
        // Check cache first
        F cachedFactory = factoryCache.get(serverId);
        if (cachedFactory != null) {
            LOG.debugf("Using cached factory for %s: %s", serverId, cachedFactory.getClass().getSimpleName());
            return cachedFactory;
        }

        // Iterate through SPI factories to find one that can handle this config
        for (F factory : spiFactories) {
            if (factory.canHandle(config, workspace)) {
                LOG.infof("Factory %s can handle %s", factory.getClass().getSimpleName(), serverId);
                factoryCache.put(serverId, factory);
                return factory;
            }
        }

        // Ultimate fallback - default factory
        F defaultFactory = getDefaultFactory();
        LOG.debugf("No specific factory found for %s, using default factory", serverId);
        factoryCache.put(serverId, defaultFactory);
        return defaultFactory;
    }

    /**
     * Get the default fallback factory.
     */
    protected abstract F getDefaultFactory();

    /**
     * Create a server instance using the appropriate factory.
     *
     * @param params Server creation parameters
     * @return The created server
     */
    public S createServer(P params) {
        F factory = findFactory(params.getConfig(), params.getWorkspace(), params.getConfig().getServerId());
        return factory.createServer(params);
    }
}
