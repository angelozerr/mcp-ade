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
package org.eclipse.mcp.ade.extensions.dotnet.installer;

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
 * Asset fetcher for .NET SDK/runtime binaries using the official releases-index.json API.
 * <p>
 * Fetches the latest LTS .NET release from
 * {@code https://raw.githubusercontent.com/dotnet/core/main/release-notes/releases-index.json}
 * and resolves the download URL for the current OS and architecture.
 */
public class DotnetAssetFetcher implements AssetFetcher {

    private static final String RELEASES_INDEX_URL =
            "https://raw.githubusercontent.com/dotnet/core/main/release-notes/releases-index.json";

    private final String releaseType;
    private final String component;

    private JsonArray releasesIndex;

    public DotnetAssetFetcher(String releaseType, String component) {
        this.releaseType = releaseType;
        this.component = component;
    }

    @Override
    public String getDownloadUrl(Function<JsonObject, Boolean> releaseMatcher,
                                 Function<JsonObject, Boolean> assetMatcher,
                                 Reporter reporter) {
        try {
            JsonArray index = getOrLoadReleasesIndex(reporter);
            if (index == null) {
                return null;
            }

            reporter.setText("> Searching for latest " + releaseType.toUpperCase() + " .NET channel...");

            JsonObject channel = findChannel(index);
            if (channel == null) {
                reporter.setText("No active " + releaseType.toUpperCase() + " .NET channel found");
                return null;
            }

            String channelVersion = channel.get("channel-version").getAsString();
            String latestRuntime = channel.get("latest-runtime").getAsString();
            reporter.setText("> Found .NET " + channelVersion + " (runtime " + latestRuntime + ")");

            String releasesJsonUrl = channel.get("releases.json").getAsString();
            JsonObject releasesJson = fetchJson(releasesJsonUrl, reporter);
            if (releasesJson == null) {
                return null;
            }

            JsonArray releases = releasesJson.getAsJsonArray("releases");
            if (releases == null || releases.isEmpty()) {
                reporter.setText("No releases found for .NET " + channelVersion);
                return null;
            }

            JsonObject latestRelease = releases.get(0).getAsJsonObject();
            String rid = getDotnetRid();
            reporter.setText("> Looking for " + component + " download for " + rid + "...");

            String url = findDownloadUrl(latestRelease, rid);
            if (url != null) {
                reporter.setText(".NET download URL: " + url);
                return url;
            }

            reporter.setText("No .NET " + component + " found for " + rid);
        } catch (Exception e) {
            reporter.setText("Error fetching .NET release info", e);
        }
        return null;
    }

    private JsonObject findChannel(JsonArray index) {
        for (JsonElement elem : index) {
            JsonObject channel = elem.getAsJsonObject();
            String supportPhase = channel.has("support-phase")
                    ? channel.get("support-phase").getAsString() : "";
            String type = channel.has("release-type")
                    ? channel.get("release-type").getAsString() : "";

            if (releaseType.equals(type) && "active".equals(supportPhase)) {
                return channel;
            }
        }
        for (JsonElement elem : index) {
            JsonObject channel = elem.getAsJsonObject();
            String supportPhase = channel.has("support-phase")
                    ? channel.get("support-phase").getAsString() : "";
            String type = channel.has("release-type")
                    ? channel.get("release-type").getAsString() : "";

            if (releaseType.equals(type) && "maintenance".equals(supportPhase)) {
                return channel;
            }
        }
        return null;
    }

    private String findDownloadUrl(JsonObject release, String rid) {
        JsonObject componentObj = release.getAsJsonObject(component);
        if (componentObj == null) {
            return null;
        }
        JsonArray files = componentObj.getAsJsonArray("files");
        if (files == null) {
            return null;
        }
        for (JsonElement fileElem : files) {
            JsonObject file = fileElem.getAsJsonObject();
            String fileRid = file.has("rid") ? file.get("rid").getAsString() : "";
            String name = file.has("name") ? file.get("name").getAsString() : "";

            if (rid.equals(fileRid) && isArchive(name)) {
                return file.get("url").getAsString();
            }
        }
        return null;
    }

    private static boolean isArchive(String name) {
        return name.endsWith(".zip") || name.endsWith(".tar.gz");
    }

    private static String getDotnetRid() {
        String os;
        if (OSUtils.isWindows()) {
            os = "win";
        } else if (OSUtils.isMac()) {
            os = "osx";
        } else {
            os = "linux";
        }

        String arch;
        if ("arm64".equals(OSUtils.ARCH_KEY)) {
            arch = "arm64";
        } else {
            arch = "x64";
        }

        return os + "-" + arch;
    }

    private JsonArray getOrLoadReleasesIndex(Reporter reporter) throws Exception {
        if (releasesIndex != null) {
            return releasesIndex;
        }
        reporter.setText("> Loading .NET releases index: " + RELEASES_INDEX_URL);

        JsonObject json = fetchJson(RELEASES_INDEX_URL, reporter);
        if (json == null) {
            return null;
        }
        releasesIndex = json.getAsJsonArray("releases-index");
        return releasesIndex;
    }

    private static JsonObject fetchJson(String urlStr, Reporter reporter) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                reporter.setText("HTTP " + responseCode + " from " + urlStr);
                return null;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                return JsonParser.parseString(sb.toString()).getAsJsonObject();
            }
        } finally {
            conn.disconnect();
        }
    }
}
