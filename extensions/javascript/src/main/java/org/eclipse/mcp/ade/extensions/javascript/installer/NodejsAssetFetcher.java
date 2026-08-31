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
package org.eclipse.mcp.ade.extensions.javascript.installer;

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
 * Asset fetcher for Node.js binaries using the official distribution API.
 * <p>
 * Node.js does not publish binary assets on GitHub releases — they are distributed
 * via {@code https://nodejs.org/dist/}. This fetcher queries the distribution index
 * to find the latest LTS (or current) version and constructs the download URL
 * for the current OS and architecture.
 */
public class NodejsAssetFetcher implements AssetFetcher {

    private static final String DIST_INDEX_URL = "https://nodejs.org/dist/index.json";
    private static final String DIST_BASE_URL = "https://nodejs.org/dist/";

    private final String releaseType;
    private JsonArray distIndex;

    public NodejsAssetFetcher(String releaseType) {
        this.releaseType = releaseType;
    }

    @Override
    public String getDownloadUrl(Function<JsonObject, Boolean> releaseMatcher,
                                 Function<JsonObject, Boolean> assetMatcher,
                                 Reporter reporter) {
        try {
            JsonArray index = getOrLoadDistIndex(reporter);
            if (index == null) {
                return null;
            }

            reporter.setText("> Searching for latest " + releaseType.toUpperCase() + " Node.js version...");

            JsonObject release = findRelease(index);
            if (release == null) {
                reporter.setText("No " + releaseType.toUpperCase() + " Node.js version found");
                return null;
            }

            String version = release.get("version").getAsString();
            JsonElement ltsElement = release.get("lts");
            String ltsName = ltsElement.isJsonPrimitive() ? ltsElement.getAsString() : "";
            reporter.setText("> Found Node.js " + version +
                    (ltsName.isEmpty() ? "" : " (" + ltsName + ")"));

            String platform = getNodejsPlatform();
            String arch = getNodejsArch();
            String ext = OSUtils.isWindows() ? "zip" : "tar.gz";

            String fileName = "node-" + version + "-" + platform + "-" + arch + "." + ext;
            String url = DIST_BASE_URL + version + "/" + fileName;

            if (!hasFile(release, platform, arch)) {
                reporter.setText("No Node.js build available for " + platform + "-" + arch);
                return null;
            }

            reporter.setText("Node.js download URL: " + url);
            return url;
        } catch (Exception e) {
            reporter.setText("Error fetching Node.js release info", e);
        }
        return null;
    }

    private JsonObject findRelease(JsonArray index) {
        for (JsonElement elem : index) {
            JsonObject release = elem.getAsJsonObject();
            if ("lts".equals(releaseType)) {
                JsonElement lts = release.get("lts");
                if (lts != null && lts.isJsonPrimitive() && !lts.getAsString().isEmpty()) {
                    return release;
                }
            } else {
                return release;
            }
        }
        return null;
    }

    private boolean hasFile(JsonObject release, String platform, String arch) {
        JsonArray files = release.getAsJsonArray("files");
        if (files == null) {
            return false;
        }
        String fileKey;
        if (OSUtils.isWindows()) {
            fileKey = "win-" + arch + "-zip";
        } else if (OSUtils.isMac()) {
            fileKey = "osx-" + arch + "-tar";
        } else {
            fileKey = "linux-" + arch;
        }
        for (JsonElement f : files) {
            if (fileKey.equals(f.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String getNodejsPlatform() {
        if (OSUtils.isWindows()) {
            return "win";
        } else if (OSUtils.isMac()) {
            return "darwin";
        } else {
            return "linux";
        }
    }

    private static String getNodejsArch() {
        if ("arm64".equals(OSUtils.ARCH_KEY)) {
            return "arm64";
        }
        return "x64";
    }

    private JsonArray getOrLoadDistIndex(Reporter reporter) throws Exception {
        if (distIndex != null) {
            return distIndex;
        }
        reporter.setText("> Loading Node.js distribution index: " + DIST_INDEX_URL);

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
