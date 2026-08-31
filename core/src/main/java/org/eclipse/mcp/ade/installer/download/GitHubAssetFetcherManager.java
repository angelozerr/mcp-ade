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

/**
 * Singleton cache for {@link GitHubAssetFetcher} instances, keyed by owner and repository.
 */
public class GitHubAssetFetcherManager extends AssetFetcherCache<GitHubAssetFetcher> {

    private static final GitHubAssetFetcherManager INSTANCE = new GitHubAssetFetcherManager();

    private GitHubAssetFetcherManager() {
    }

    public static GitHubAssetFetcherManager getInstance() {
        return INSTANCE;
    }

    public GitHubAssetFetcher getAssetFetcher(String owner, String repository) {
        return get(owner, repository, GitHubAssetFetcher::new);
    }
}
