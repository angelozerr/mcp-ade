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
package org.eclipse.mcp.ade.installer.task;

import com.google.gson.JsonElement;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Registry for installer task factories.
 * Uses Gson for JSON parsing.
 */
public class InstallerTaskRegistry {
    private static final Logger LOG = Logger.getLogger(InstallerTaskRegistry.class);

    private final Map<String, InstallerTaskFactory> factories = new HashMap<>();

    public InstallerTaskRegistry() {
        loadFactories();
    }

    private void loadFactories() {
        ServiceLoader<InstallerTaskFactory> loader = ServiceLoader.load(
                InstallerTaskFactory.class, InstallerTaskFactory.class.getClassLoader());
        for (InstallerTaskFactory factory : loader) {
            String type = factory.getType();
            factories.put(type, factory);
            LOG.debugf("Registered installer task factory: %s", type);
        }
    }

    /**
     * Creates a task from JSON configuration.
     *
     * @param type Task type (e.g., "download", "fileExists")
     * @param config JSON configuration (Gson JsonElement)
     * @return The created task, or null if factory not found
     */
    public InstallerTask createTask(String type, JsonElement config) {
        InstallerTaskFactory factory = factories.get(type);
        if (factory == null) {
            LOG.errorf("No factory found for task type: %s", type);
            return null;
        }
        return factory.createTask(config);
    }

    /**
     * Checks if a factory exists for the given type.
     */
    public boolean hasFactory(String type) {
        return factories.containsKey(type);
    }

    /**
     * Returns the set of registered task type names (for diagnostics).
     */
    public java.util.Set<String> getRegisteredTypes() {
        return factories.keySet();
    }
}
