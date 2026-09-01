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
package org.eclipse.mcp.ade.extensions.dart.installer;

import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.installer.download.AssetFetcherInfo;
import org.eclipse.mcp.ade.installer.download.AssetFetcherProvider;

/**
 * {@link AssetFetcherProvider} for Dart SDK binaries.
 * <p>
 * Recognizes the {@code "dart"} JSON property in download task configuration
 * and provides a {@link DartAssetFetcher} that resolves the latest stable Dart SDK
 * from the official archive at {@code https://storage.googleapis.com/dart-archive/}.
 * <p>
 * Supported configuration:
 * <pre>
 * "dart": {}        // downloads latest stable Dart SDK
 * </pre>
 */
public class DartAssetFetcherProvider implements AssetFetcherProvider {

    private static final String DART_JSON_PROPERTY = "dart";

    private DartAssetFetcher assetFetcher;

    @Override
    public AssetFetcherInfo getAssetFetcher(JsonObject downloadJson) {
        if (!downloadJson.has(DART_JSON_PROPERTY)) {
            return null;
        }

        return new AssetFetcherInfo(getOrCreateFetcher(),
                obj -> true,
                obj -> true);
    }

    private synchronized DartAssetFetcher getOrCreateFetcher() {
        if (assetFetcher == null) {
            assetFetcher = new DartAssetFetcher();
        }
        return assetFetcher;
    }
}
