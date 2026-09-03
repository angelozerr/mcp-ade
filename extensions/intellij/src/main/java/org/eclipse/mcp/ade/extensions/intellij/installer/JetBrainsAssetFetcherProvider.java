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
package org.eclipse.mcp.ade.extensions.intellij.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.installer.download.AssetFetcherInfo;
import org.eclipse.mcp.ade.installer.download.AssetFetcherProvider;

/**
 * {@link AssetFetcherProvider} for JetBrains IntelliJ Language Server.
 * <p>
 * Recognizes the {@code "jetbrains"} JSON property with {@code "namespace"} and
 * {@code "extensionName"} fields. Downloads the VSIX from Open VSX, extracts
 * the {@code server-bundle.json}, and resolves the actual server binary URL.
 */
public class JetBrainsAssetFetcherProvider implements AssetFetcherProvider {

    private static final String JETBRAINS_JSON_PROPERTY = "jetbrains";

    private JetBrainsAssetFetcher assetFetcher;

    @Override
    public AssetFetcherInfo getAssetFetcher(JsonObject downloadJson) {
        if (!downloadJson.has(JETBRAINS_JSON_PROPERTY)) {
            return null;
        }
        JsonElement element = downloadJson.get(JETBRAINS_JSON_PROPERTY);
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject config = element.getAsJsonObject();
        String namespace = config.has("namespace") ? config.get("namespace").getAsString() : "JetBrains";
        String extensionName = config.has("extensionName") ? config.get("extensionName").getAsString() : "intellij-server";
        boolean targetPlatform = config.has("targetPlatform") && config.get("targetPlatform").getAsBoolean();

        return new AssetFetcherInfo(getOrCreateFetcher(namespace, extensionName, targetPlatform),
                obj -> true,
                obj -> true);
    }

    private synchronized JetBrainsAssetFetcher getOrCreateFetcher(String namespace, String extensionName, boolean targetPlatform) {
        if (assetFetcher == null) {
            assetFetcher = new JetBrainsAssetFetcher(namespace, extensionName, targetPlatform);
        }
        return assetFetcher;
    }
}
