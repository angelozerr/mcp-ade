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
package com.ibm.mcp.languagetools.extensions.javascript.installer;

import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.installer.download.AssetFetcherInfo;
import com.ibm.mcp.languagetools.installer.download.AssetFetcherProvider;

/**
 * {@link AssetFetcherProvider} for Node.js runtime binaries.
 * <p>
 * Recognizes the {@code "nodejs"} JSON property in download task configuration
 * and provides a {@link NodejsAssetFetcher} that resolves the latest Node.js release
 * from the official distribution API at {@code https://nodejs.org/dist/index.json}.
 * <p>
 * Supported configuration:
 * <pre>
 * "nodejs": {}                                // defaults: LTS
 * "nodejs": { "release-type": "lts" }         // explicit LTS
 * "nodejs": { "release-type": "current" }     // latest current release
 * </pre>
 */
public class NodejsAssetFetcherProvider implements AssetFetcherProvider {

    private static final String NODEJS_JSON_PROPERTY = "nodejs";
    private static final String DEFAULT_RELEASE_TYPE = "lts";

    private NodejsAssetFetcher assetFetcher;
    private String cachedReleaseType;

    @Override
    public AssetFetcherInfo getAssetFetcher(JsonObject downloadJson) {
        if (!downloadJson.has(NODEJS_JSON_PROPERTY)) {
            return null;
        }

        JsonObject nodejsConfig = downloadJson.getAsJsonObject(NODEJS_JSON_PROPERTY);
        String releaseType = getStringOrDefault(nodejsConfig, "release-type", DEFAULT_RELEASE_TYPE);

        return new AssetFetcherInfo(getOrCreateFetcher(releaseType),
                obj -> true,
                obj -> true);
    }

    private synchronized NodejsAssetFetcher getOrCreateFetcher(String releaseType) {
        if (assetFetcher == null || !releaseType.equals(cachedReleaseType)) {
            cachedReleaseType = releaseType;
            assetFetcher = new NodejsAssetFetcher(releaseType);
        }
        return assetFetcher;
    }

    private static String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        if (obj != null && obj.has(key)) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }
}
