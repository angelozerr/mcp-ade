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
package com.ibm.mcp.languagetools.installer.download;

import com.google.gson.JsonObject;

/**
 * SPI for providing {@link AssetFetcherInfo} from a download task JSON configuration.
 * <p>
 * Each provider checks for its own JSON property (e.g., "github", "maven", "openvsx")
 * and returns an {@link AssetFetcherInfo} if the property is present, or {@code null} otherwise.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader}.
 */
public interface AssetFetcherProvider {

    /**
     * Returns an {@link AssetFetcherInfo} if this provider can handle the given download JSON,
     * or {@code null} if it does not recognize any relevant property.
     *
     * @param downloadJson the JSON object of the download task configuration
     * @return asset fetcher info, or null
     */
    AssetFetcherInfo getAssetFetcher(JsonObject downloadJson);
}
