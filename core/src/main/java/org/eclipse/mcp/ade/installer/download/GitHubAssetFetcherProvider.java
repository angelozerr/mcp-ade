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
import org.eclipse.mcp.ade.utils.OSUtils;

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
    private static final String GITHUB_TAG_PATTERN_JSON_PROPERTY = "tagPattern";

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
        String owner = OSUtils.getStringFromOs(githubObj, GITHUB_OWNER_JSON_PROPERTY);
        String repository = OSUtils.getStringFromOs(githubObj, GITHUB_REPOSITORY_JSON_PROPERTY);
        if (owner == null || repository == null) {
            return null;
        }
        String assetPattern = OSUtils.getStringFromOs(githubObj, GITHUB_ASSET_JSON_PROPERTY);
        if (assetPattern == null) {
            return null;
        }
        boolean prerelease = githubObj.has(GITHUB_PRERELEASE_JSON_PROPERTY)
                && githubObj.get(GITHUB_PRERELEASE_JSON_PROPERTY).getAsBoolean();
        String tagPattern = OSUtils.getStringFromOs(githubObj, GITHUB_TAG_PATTERN_JSON_PROPERTY);

        var assetFetcher = GitHubAssetFetcherManager.getInstance().getAssetFetcher(owner, repository);
        return new AssetFetcherInfo(assetFetcher,
                new GitHubAssetFetcher.ReleaseMatcher(prerelease, tagPattern),
                new GitHubAssetFetcher.AssetMatcher(assetPattern));
    }
}
