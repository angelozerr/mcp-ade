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
package com.ibm.mcp.languagetools.configuration;

import org.eclipse.lsp4j.ConfigurationItem;

import java.util.List;
import java.util.Map;

/**
 * Read-only configuration interface.
 * Implemented by both {@link ApplicationConfiguration} and
 * {@link com.ibm.mcp.languagetools.workspace.IdeConfiguration}.
 */
public interface Configuration {

    Object get(String key);

    Object get(String key, Object defaultValue);

    String getString(String key);

    String getString(String key, String defaultValue);

    boolean getBoolean(String key, boolean defaultValue);

    int getInt(String key, int defaultValue);

    Map<String, Object> getAll();

    /**
     * Find settings for the given LSP configuration item.
     * Resolves the item's section using direct key match, dot-notation traversal,
     * and flat key prefix matching.
     *
     * @param item the LSP configuration item
     * @return the matching value, or null if not found
     */
    Object find(ConfigurationItem item);

    List<Object> find(List<ConfigurationItem> items);

    void reload();

    void watch();

    void unwatch();
}
