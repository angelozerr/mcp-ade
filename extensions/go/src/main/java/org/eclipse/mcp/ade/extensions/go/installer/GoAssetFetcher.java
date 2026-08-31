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
package org.eclipse.mcp.ade.extensions.go.installer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.mcp.ade.installer.download.AssetFetcher;
import org.eclipse.mcp.ade.utils.OSUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Function;

/**
 * Asset fetcher for Go binaries using the official distribution API.
 * <p>
 * Go distributes binaries via {@code https://go.dev/dl/}. This fetcher queries
 * the distribution index to find the latest stable version and constructs
 * the download URL for the current OS and architecture.
 */
public class GoAssetFetcher implements AssetFetcher {

    private static final String DIST_INDEX_URL = "https://go.dev/dl/?mode=json";
    private static final String DIST_BASE_URL = "https://go.dev/dl/";

    private JsonArray distIndex;

    @Override
    public String getDownloadUrl(Function<JsonObject, Boolean> releaseMatcher,
                                 Function<JsonObject, Boolean> assetMatcher,
                                 Reporter reporter) {
        try {
            JsonArray index = getOrLoadDistIndex(reporter);
            if (index == null) {
                return null;
            }

            reporter.setText("> Searching for latest stable Go version...");

            JsonObject release = findStableRelease(index);
            if (release == null) {
                reporter.setText("No stable Go version found");
                return null;
            }

            String version = release.get("version").getAsString();
            reporter.setText("> Found Go " + version);

            String goOs = getGoOs();
            String goArch = getGoArch();

            JsonObject file = findArchiveFile(release, goOs, goArch);
            if (file == null) {
                reporter.setText("No Go build available for " + goOs + "-" + goArch);
                return null;
            }

            String filename = file.get("filename").getAsString();
            String url = DIST_BASE_URL + filename;

            reporter.setText("Go download URL: " + url);
            return url;
        } catch (Exception e) {
            reporter.setText("Error fetching Go release info", e);
        }
        return null;
    }

    private JsonObject findStableRelease(JsonArray index) {
        for (JsonElement elem : index) {
            JsonObject release = elem.getAsJsonObject();
            if (release.has("stable") && release.get("stable").getAsBoolean()) {
                return release;
            }
        }
        return null;
    }

    private JsonObject findArchiveFile(JsonObject release, String goOs, String goArch) {
        JsonArray files = release.getAsJsonArray("files");
        if (files == null) {
            return null;
        }
        for (JsonElement elem : files) {
            JsonObject file = elem.getAsJsonObject();
            if (goOs.equals(file.get("os").getAsString())
                    && goArch.equals(file.get("arch").getAsString())
                    && "archive".equals(file.get("kind").getAsString())) {
                return file;
            }
        }
        return null;
    }

    private static String getGoOs() {
        if (OSUtils.isWindows()) {
            return "windows";
        } else if (OSUtils.isMac()) {
            return "darwin";
        } else {
            return "linux";
        }
    }

    private static String getGoArch() {
        if ("arm64".equals(OSUtils.ARCH_KEY)) {
            return "arm64";
        }
        return "amd64";
    }

    private JsonArray getOrLoadDistIndex(Reporter reporter) throws Exception {
        if (distIndex != null) {
            return distIndex;
        }
        reporter.setText("> Loading Go distribution index: " + DIST_INDEX_URL);

        URL url = new URL(DIST_INDEX_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                reporter.setText("HTTP " + responseCode + " from " + DIST_INDEX_URL);
                return null;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                distIndex = JsonParser.parseString(sb.toString()).getAsJsonArray();
                return distIndex;
            }
        } finally {
            conn.disconnect();
        }
    }
}
