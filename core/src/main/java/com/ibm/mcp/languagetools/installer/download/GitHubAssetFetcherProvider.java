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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.utils.OSUtils;

/**
 * {@link AssetFetcherProvider} for GitHub releases.
 * <p>
 * Recognizes the {@code "github"} JSON property with {@code "owner"}, {@code "repository"},
 * and {@code "asset"} fields.
 */
public class GitHubAssetFetcherProvider implements AssetFetcherProvider {

    private static final String GITHUB_JSON_PROPERTY = "github";
    private static final String GITHUB_OWNER_JSON_PROPERTY = "owner";
    private static final String GITHUB_REPOSITORY_JSON_PROPERTY = "repository";
    private static final String GITHUB_ASSET_JSON_PROPERTY = "asset";
    private static final String GITHUB_PRERELEASE_JSON_PROPERTY = "prerelease";

    @Override
    public AssetFetcherInfo getAssetFetcher(JsonObject downloadJson) {
        if (!downloadJson.has(GITHUB_JSON_PROPERTY)) {
            return null;
        }
        JsonElement githubElement = downloadJson.get(GITHUB_JSON_PROPERTY);
        if (!githubElement.isJsonObject()) {
            return null;
        }
        JsonObject githubObj = githubElement.getAsJsonObject();
        if (!githubObj.has(GITHUB_OWNER_JSON_PROPERTY) || !githubObj.has(GITHUB_REPOSITORY_JSON_PROPERTY)) {
            return null;
        }
        String owner = githubObj.get(GITHUB_OWNER_JSON_PROPERTY).getAsString();
        String repository = githubObj.get(GITHUB_REPOSITORY_JSON_PROPERTY).getAsString();
        String assetPattern = OSUtils.getStringFromOs(githubObj, GITHUB_ASSET_JSON_PROPERTY);
        if (assetPattern == null) {
            return null;
        }
        boolean prerelease = githubObj.has(GITHUB_PRERELEASE_JSON_PROPERTY)
                && githubObj.get(GITHUB_PRERELEASE_JSON_PROPERTY).getAsBoolean();

        var assetFetcher = GitHubAssetFetcherManager.getInstance().getAssetFetcher(owner, repository);
        return new AssetFetcherInfo(assetFetcher,
                new GitHubAssetFetcher.ReleaseMatcher(prerelease),
                new GitHubAssetFetcher.AssetMatcher(assetPattern));
    }
}
