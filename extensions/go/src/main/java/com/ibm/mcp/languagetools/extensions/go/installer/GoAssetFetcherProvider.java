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
package com.ibm.mcp.languagetools.extensions.go.installer;

import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.installer.download.AssetFetcherInfo;
import com.ibm.mcp.languagetools.installer.download.AssetFetcherProvider;

/**
 * {@link AssetFetcherProvider} for Go runtime binaries.
 * <p>
 * Recognizes the {@code "go"} JSON property in download task configuration
 * and provides a {@link GoAssetFetcher} that resolves the latest stable Go release
 * from the official distribution API at {@code https://go.dev/dl/?mode=json}.
 * <p>
 * Supported configuration:
 * <pre>
 * "go": {}        // downloads latest stable Go release
 * </pre>
 */
public class GoAssetFetcherProvider implements AssetFetcherProvider {

    private static final String GO_JSON_PROPERTY = "go";

    private GoAssetFetcher assetFetcher;

    @Override
    public AssetFetcherInfo getAssetFetcher(JsonObject downloadJson) {
        if (!downloadJson.has(GO_JSON_PROPERTY)) {
            return null;
        }

        return new AssetFetcherInfo(getOrCreateFetcher(),
                obj -> true,
                obj -> true);
    }

    private synchronized GoAssetFetcher getOrCreateFetcher() {
        if (assetFetcher == null) {
            assetFetcher = new GoAssetFetcher();
        }
        return assetFetcher;
    }
}
