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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * {@link AssetFetcherProvider} for Open VSX Registry extensions.
 * <p>
 * Recognizes the {@code "openvsx"} JSON property with {@code "namespace"} and {@code "extensionName"} fields.
 */
public class OpenVsxAssetFetcherProvider implements AssetFetcherProvider {

    private static final String OPENVSX_JSON_PROPERTY = "openvsx";
    private static final String OPENVSX_NAMESPACE_JSON_PROPERTY = "namespace";
    private static final String OPENVSX_EXTENSION_NAME_JSON_PROPERTY = "extensionName";

    @Override
    public AssetFetcherInfo getAssetFetcher(JsonObject downloadJson) {
        if (!downloadJson.has(OPENVSX_JSON_PROPERTY)) {
            return null;
        }
        JsonElement openvsxElement = downloadJson.get(OPENVSX_JSON_PROPERTY);
        if (!openvsxElement.isJsonObject()) {
            return null;
        }
        JsonObject openvsxObj = openvsxElement.getAsJsonObject();
        if (!openvsxObj.has(OPENVSX_NAMESPACE_JSON_PROPERTY) || !openvsxObj.has(OPENVSX_EXTENSION_NAME_JSON_PROPERTY)) {
            return null;
        }
        String namespace = openvsxObj.get(OPENVSX_NAMESPACE_JSON_PROPERTY).getAsString();
        String extensionName = openvsxObj.get(OPENVSX_EXTENSION_NAME_JSON_PROPERTY).getAsString();

        var assetFetcher = OpenVsxAssetFetcherManager.getInstance().getAssetFetcher(namespace, extensionName);
        return new AssetFetcherInfo(assetFetcher,
                obj -> true,
                obj -> true);
    }
}
