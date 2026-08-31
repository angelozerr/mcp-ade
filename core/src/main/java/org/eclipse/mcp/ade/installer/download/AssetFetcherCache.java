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
package org.eclipse.mcp.ade.installer.download;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Generic singleton cache for asset fetchers.
 * <p>
 * Ensures that a unique fetcher is created per (key1, key2) pair and reused thereafter.
 *
 * @param <T> the fetcher type
 */
public class AssetFetcherCache<T> {

    private final Map<String, T> fetchers = new ConcurrentHashMap<>();

    protected T get(String key1, String key2, BiFunction<String, String, T> factory) {
        String key = key1 + "#" + key2;
        return fetchers.computeIfAbsent(key, k -> factory.apply(key1, key2));
    }
}
