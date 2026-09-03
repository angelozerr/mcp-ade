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

import com.google.gson.JsonObject;

import java.util.function.Function;

/**
 * Interface for fetching download URLs of assets from various sources.
 */
public interface AssetFetcher {

    /**
     * Interface for reporting progress and status messages.
     */
    interface Reporter {
        void setText(String text);
        void setText(String text, Exception e);
        default void setUpdateText(String text) {
            setText(text);
        }
    }

    /**
     * Finds the download URL for an asset matching the given filters.
     *
     * @param releaseMatcher filter to select the desired release
     * @param assetMatcher filter to select the desired asset
     * @param reporter object to report progress or status messages
     * @return the download URL of the matching asset, or null if none found
     */
    String getDownloadUrl(Function<JsonObject, Boolean> releaseMatcher,
                                     Function<JsonObject, Boolean> assetMatcher,
                                     Reporter reporter);
}
