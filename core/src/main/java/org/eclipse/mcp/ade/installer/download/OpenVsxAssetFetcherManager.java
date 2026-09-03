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

/**
 * Singleton cache for {@link OpenVsxAssetFetcher} instances, keyed by namespace and extension name.
 */
public class OpenVsxAssetFetcherManager extends AssetFetcherCache<OpenVsxAssetFetcher> {

    private static final OpenVsxAssetFetcherManager INSTANCE = new OpenVsxAssetFetcherManager();

    private OpenVsxAssetFetcherManager() {
    }

    public static OpenVsxAssetFetcherManager getInstance() {
        return INSTANCE;
    }

    public OpenVsxAssetFetcher getAssetFetcher(String namespace, String extensionName, boolean targetPlatform) {
        return get(namespace, extensionName,
                (ns, ext) -> new OpenVsxAssetFetcher(ns, ext, targetPlatform));
    }
}
