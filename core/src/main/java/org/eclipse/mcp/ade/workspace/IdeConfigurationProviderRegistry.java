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
package org.eclipse.mcp.ade.workspace;

import org.jboss.logging.Logger;

import java.util.*;

/**
 * Registry for IDE configuration providers discovered via Java SPI (ServiceLoader).
 */
public class IdeConfigurationProviderRegistry {

    private static final Logger LOG = Logger.getLogger(IdeConfigurationProviderRegistry.class);
    private static final IdeConfigurationProviderRegistry INSTANCE = new IdeConfigurationProviderRegistry();

    private final Map<String, IdeConfigurationProvider> providers = new LinkedHashMap<>();

    private IdeConfigurationProviderRegistry() {
        ServiceLoader<IdeConfigurationProvider> loader = ServiceLoader.load(IdeConfigurationProvider.class);
        for (IdeConfigurationProvider provider : loader) {
            providers.put(provider.getId(), provider);
            LOG.infof("Registered IDE configuration provider: %s", provider.getId());
        }
    }

    public static IdeConfigurationProviderRegistry getInstance() {
        return INSTANCE;
    }

    public IdeConfigurationProvider getProvider(String id) {
        return providers.get(id);
    }

    public Collection<IdeConfigurationProvider> getProviders() {
        return Collections.unmodifiableCollection(providers.values());
    }

    /**
     * Returns provider ids in SPI discovery order.
     */
    public List<String> getProviderIds() {
        return List.copyOf(providers.keySet());
    }
}
