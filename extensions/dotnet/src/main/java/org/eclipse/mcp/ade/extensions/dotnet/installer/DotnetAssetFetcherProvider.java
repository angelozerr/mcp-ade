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
package org.eclipse.mcp.ade.extensions.dotnet.installer;

import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.installer.download.AssetFetcherInfo;
import org.eclipse.mcp.ade.installer.download.AssetFetcherProvider;

/**
 * {@link AssetFetcherProvider} for .NET SDK/runtime binaries.
 * <p>
 * Recognizes the {@code "dotnet"} JSON property in download task configuration
 * and provides a {@link DotnetAssetFetcher} that resolves the latest .NET release
 * from the official releases-index.json API.
 * <p>
 * Supported configuration:
 * <pre>
 * "dotnet": {}                                          // defaults: LTS SDK
 * "dotnet": { "release-type": "lts", "component": "sdk" }  // explicit
 * </pre>
 */
public class DotnetAssetFetcherProvider implements AssetFetcherProvider {

    private static final String DOTNET_JSON_PROPERTY = "dotnet";
    private static final String DEFAULT_RELEASE_TYPE = "lts";
    private static final String DEFAULT_COMPONENT = "sdk";

    private DotnetAssetFetcher assetFetcher;
    private String cachedReleaseType;
    private String cachedComponent;

    @Override
    public AssetFetcherInfo getAssetFetcher(JsonObject downloadJson) {
        if (!downloadJson.has(DOTNET_JSON_PROPERTY)) {
            return null;
        }

        JsonObject dotnetConfig = downloadJson.getAsJsonObject(DOTNET_JSON_PROPERTY);
        String releaseType = getStringOrDefault(dotnetConfig, "release-type", DEFAULT_RELEASE_TYPE);
        String component = getStringOrDefault(dotnetConfig, "component", DEFAULT_COMPONENT);

        return new AssetFetcherInfo(getOrCreateFetcher(releaseType, component),
                obj -> true,
                obj -> true);
    }

    private synchronized DotnetAssetFetcher getOrCreateFetcher(String releaseType, String component) {
        if (assetFetcher == null
                || !releaseType.equals(cachedReleaseType)
                || !component.equals(cachedComponent)) {
            cachedReleaseType = releaseType;
            cachedComponent = component;
            assetFetcher = new DotnetAssetFetcher(releaseType, component);
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
