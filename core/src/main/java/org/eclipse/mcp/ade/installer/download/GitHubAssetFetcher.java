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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Utility class to fetch the download URL of a GitHub asset from the releases of a given repository.
 *
 * <p>This class queries the GitHub API to retrieve the list of releases and selects an asset
 * based on customizable filtering criteria using {@link Function} filters.</p>
 */
public class GitHubAssetFetcher implements AssetFetcher {

    /**
     * Default filter that matches only non-prerelease releases.
     */
    public static final Function<JsonObject, Boolean> RELEASE_MATCHER = new ReleaseMatcher(false);

    /**
     * Filter that matches only prerelease releases.
     */
    public static final Function<JsonObject, Boolean> PRERELEASE_MATCHER = new ReleaseMatcher(true);

    /**
     * Implementation of {@link Function} to filter releases based on whether they are prereleases.
     */
    public static class ReleaseMatcher implements Function<JsonObject, Boolean> {

        private final boolean prerelease;
        private final Pattern tagPattern;

        public ReleaseMatcher(boolean prerelease) {
            this(prerelease, null);
        }

        public ReleaseMatcher(boolean prerelease, String tagGlob) {
            this.prerelease = prerelease;
            this.tagPattern = tagGlob != null ? compileGlobToRegex(tagGlob) : null;
        }

        @Override
        public Boolean apply(JsonObject release) {
            if (prerelease != getBoolean(release, "prerelease")) {
                return false;
            }
            if (tagPattern != null) {
                String tagName = release.has("tag_name") ? release.get("tag_name").getAsString() : "";
                return tagPattern.matcher(tagName).matches();
            }
            return true;
        }

        private static Pattern compileGlobToRegex(String glob) {
            String regex = glob
                    .replace(".", "\\.")
                    .replace("*", ".*");
            return Pattern.compile(regex);
        }
    }

    /**
     * Implementation of {@link Function} to filter assets (files) within a release
     * by either a simple substring match or a glob pattern.
     */
    public static class AssetMatcher implements Function<JsonObject, Boolean> {

        private final String assetPattern;
        private final Pattern globPattern;

        /**
         * Constructs an asset filter using either a simple substring or glob pattern.
         *
         * <p>If the pattern contains '*', it is treated as a glob pattern, otherwise as a substring.</p>
         *
         * @param assetPattern the pattern to match asset names against (glob or substring)
         */
        public AssetMatcher(String assetPattern) {
            if (assetPattern.contains("*")) {
                this.assetPattern = null;
                this.globPattern = compileGlobToRegex(assetPattern);
            } else {
                this.assetPattern = assetPattern;
                this.globPattern = null;
            }
        }

        /**
         * Applies the filter to a given asset JSON object.
         *
         * @param asset the JSON object representing an asset (file) in the release
         * @return true if the asset name matches the filter pattern, false otherwise
         */
        @Override
        public Boolean apply(JsonObject asset) {
            String name = asset.get("name").getAsString();
            if (globPattern != null) {
                return globPattern.matcher(name).matches();
            }
            if (assetPattern != null) {
                return name.toLowerCase().contains(assetPattern.toLowerCase());
            }
            return false;
        }

        /**
         * Converts a simple glob pattern to a compiled regular expression.
         *
         * <p>Currently supports only '*' as a wildcard matching any sequence of characters.</p>
         *
         * @param glob the glob pattern to convert
         * @return the compiled regex {@link Pattern}
         */
        private static Pattern compileGlobToRegex(String glob) {
            // Escape '.' and replace '*' with '.*' for regex matching
            String regex = glob
                    .replace(".", "\\.")    // Escape dot character
                    .replace("*", ".*");    // Replace '*' with regex wildcard
            return Pattern.compile(regex);
        }
    }

    private final String owner;
    private final String repository;
    private JsonArray releases;

    /**
     * Constructs a GitHubAssetFetcher for a given repository owner and name.
     *
     * @param owner the GitHub repository owner
     * @param repository the GitHub repository name
     */
    public GitHubAssetFetcher(String owner, String repository) {
        this.owner = owner;
        this.repository = repository;
    }

    /**
     * Finds the download URL for an asset in the latest non-prerelease release matching the given asset filter.
     *
     * @param assetMatcher the filter to select the desired asset
     * @param reporter an object to report progress or status messages
     * @return the download URL of the matching asset, or null if none found
     */
    public String getDownloadUrl(Function<JsonObject, Boolean> assetMatcher,
                                           Reporter reporter) {
        return getDownloadUrl(RELEASE_MATCHER, assetMatcher, reporter);
    }

    /**
     * Finds the download URL for an asset in the latest release matching the given release filter
     * and asset filter.
     *
     * @param releaseMatcher the filter to select the desired release (e.g. prerelease or not)
     * @param assetMatcher the filter to select the desired asset within the release
     * @param reporter an object to report progress or status messages
     * @return the download URL of the matching asset, or null if none found
     */
    @Override
    public String getDownloadUrl(Function<JsonObject, Boolean> releaseMatcher,
                                           Function<JsonObject, Boolean> assetMatcher,
                                           Reporter reporter) {
        try {
            JsonArray releases = getOrLoadReleases(reporter);
            if (releases == null) {
                return null;
            }
            reporter.setText("> Searching GitHub asset to download....");
            for (JsonElement releaseElem : releases) {
                JsonObject release = releaseElem.getAsJsonObject();
                if (!releaseMatcher.apply(release)) {
                    continue;
                }

                JsonArray assets = release.getAsJsonArray("assets");
                for (JsonElement assetElem : assets) {
                    JsonObject asset = assetElem.getAsJsonObject();
                    if (assetMatcher.apply(asset)) {
                        String downloadUrl = asset.get("browser_download_url").getAsString();
                        reporter.setText("Asset found " + downloadUrl);
                        return downloadUrl;
                    }
                }
                break; // only check the latest matching release
            }
            reporter.setText("No asset found to download....");
        }
        catch(Exception e) {
            reporter.setText("Error while searching GitHub asset", e);
        }
        return null;
    }

    /**
     * Returns cached releases or loads them from GitHub if not already loaded.
     *
     * @param reporter an object to report progress or status messages
     * @return the JSON array of releases, or null if loading failed
     * @throws Exception if an error occurs during loading
     */
    private JsonArray getOrLoadReleases(Reporter reporter) throws Exception {
        if (releases != null) {
            return releases;
        }
        String json = fetchReleasesJson(reporter);
        if (json == null) {
            return null;
        }
        return releases = JsonParser.parseString(json).getAsJsonArray();
    }

    /**
     * Fetches the raw JSON string of releases from the GitHub API.
     *
     * @param reporter an object to report progress or status messages
     * @return the JSON string of releases, or null if an error occurred
     */
    private String fetchReleasesJson(Reporter reporter)  {
        String urlStr = "https://api.github.com/repos/" + owner + "/" + repository + "/releases";
        reporter.setText("> Loading GitHub releases: " + urlStr);

        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("GET");
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new RuntimeException("GitHub API returned HTTP " + responseCode);
                }
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                    return sb.toString();
                }
            } finally {
                conn.disconnect();
            }
        }
        catch(Exception e) {
            reporter.setText("Error while loading GitHub releases: ", e);
        }
        return null;
    }

    /**
     * Helper to get boolean from JSON object.
     */
    private static boolean getBoolean(JsonObject obj, String property) {
        if (!obj.has(property)) {
            return false;
        }
        var element = obj.get(property);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }
}
