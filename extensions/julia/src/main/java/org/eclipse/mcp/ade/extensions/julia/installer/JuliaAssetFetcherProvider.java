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
package org.eclipse.mcp.ade.extensions.julia.installer;

import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.installer.download.AssetFetcherInfo;
import org.eclipse.mcp.ade.installer.download.AssetFetcherProvider;

/**
 * {@link AssetFetcherProvider} for Julia binaries.
 * <p>
 * Recognizes the {@code "julia"} JSON property in download task configuration
 * and provides a {@link JuliaAssetFetcher} that resolves the latest stable
 * Julia binary from the official versions.json API.
 */
public class JuliaAssetFetcherProvider implements AssetFetcherProvider {

    private static final String JULIA_JSON_PROPERTY = "julia";

    private JuliaAssetFetcher assetFetcher;

    @Override
    public AssetFetcherInfo getAssetFetcher(JsonObject downloadJson) {
        if (!downloadJson.has(JULIA_JSON_PROPERTY)) {
            return null;
        }
        return new AssetFetcherInfo(getOrCreateFetcher(),
                obj -> true,
                obj -> true);
    }

    private synchronized JuliaAssetFetcher getOrCreateFetcher() {
        if (assetFetcher == null) {
            assetFetcher = new JuliaAssetFetcher();
        }
        return assetFetcher;
    }
}
