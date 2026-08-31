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
 * Holds an {@link AssetFetcher} together with its release and asset matching functions.
 */
public record AssetFetcherInfo(AssetFetcher assetFetcher,
                               Function<JsonObject, Boolean> releaseMatcher,
                               Function<JsonObject, Boolean> assetMatcher) {
}
