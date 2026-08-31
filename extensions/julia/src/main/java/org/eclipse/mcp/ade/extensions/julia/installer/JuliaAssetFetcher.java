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
package org.eclipse.mcp.ade.extensions.julia.installer;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Asset fetcher for Julia binaries using the official versions.json API.
 * <p>
 * Fetches the latest stable Julia version from
 * {@code https://julialang-s3.julialang.org/bin/versions.json}
 * and resolves the download URL for the current OS and architecture.
 */
public class JuliaAssetFetcher implements AssetFetcher {

    private static final String VERSIONS_URL = "https://julialang-s3.julialang.org/bin/versions.json";

    private JsonObject versions;

    @Override
    public String getDownloadUrl(Function<JsonObject, Boolean> releaseMatcher,
                                 Function<JsonObject, Boolean> assetMatcher,
                                 Reporter reporter) {
        try {
            JsonObject versions = getOrLoadVersions(reporter);
            if (versions == null) {
                return null;
            }

            reporter.setText("> Searching for latest stable Julia version...");

            String latestVersion = findLatestStableVersion(versions);
            if (latestVersion == null) {
                reporter.setText("No stable Julia version found");
                return null;
            }

            reporter.setText("> Latest stable Julia: " + latestVersion);

            JsonObject versionInfo = versions.getAsJsonObject(latestVersion);
            JsonArray files = versionInfo.getAsJsonArray("files");
            if (files == null) {
                reporter.setText("No files for Julia " + latestVersion);
                return null;
            }

            String osKey = getJuliaOsKey();
            String archKey = getJuliaArchKey();

            for (JsonElement fileElem : files) {
                JsonObject file = fileElem.getAsJsonObject();
                String fileOs = file.get("os").getAsString();
                String fileArch = file.get("arch").getAsString();
                String kind = file.get("kind").getAsString();

                if (osKey.equals(fileOs) && archKey.equals(fileArch) && "archive".equals(kind)) {
                    String url = file.get("url").getAsString();
                    reporter.setText("Julia download URL: " + url);
                    return url;
                }
            }

            reporter.setText("No Julia archive found for " + osKey + "/" + archKey);
        } catch (Exception e) {
            reporter.setText("Error fetching Julia version info", e);
        }
        return null;
    }

    private String findLatestStableVersion(JsonObject versions) {
        List<String> stableVersions = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : versions.entrySet()) {
            JsonObject info = entry.getValue().getAsJsonObject();
            if (info.has("stable") && info.get("stable").getAsBoolean()) {
                stableVersions.add(entry.getKey());
            }
        }
        if (stableVersions.isEmpty()) {
            return null;
        }
        stableVersions.sort((a, b) -> compareVersions(parseVersion(b), parseVersion(a)));
        return stableVersions.get(0);
    }

    private static List<Integer> parseVersion(String version) {
        List<Integer> parts = new ArrayList<>();
        for (String p : version.split("\\.")) {
            try {
                parts.add(Integer.parseInt(p));
            } catch (NumberFormatException e) {
                parts.add(0);
            }
        }
        return parts;
    }

    private static int compareVersions(List<Integer> a, List<Integer> b) {
        int len = Math.max(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            int av = i < a.size() ? a.get(i) : 0;
            int bv = i < b.size() ? b.get(i) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static String getJuliaOsKey() {
        if (OSUtils.isWindows()) return "winnt";
        if (OSUtils.isMac()) return "mac";
        return "linux";
    }

    private static String getJuliaArchKey() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            return "aarch64";
        }
        return "x86_64";
    }

    private JsonObject getOrLoadVersions(Reporter reporter) throws Exception {
        if (versions != null) {
            return versions;
        }
        reporter.setText("> Loading Julia versions: " + VERSIONS_URL);

        URL url = new URL(VERSIONS_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                reporter.setText("Julia versions API returned HTTP " + responseCode);
                return null;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                versions = JsonParser.parseString(sb.toString()).getAsJsonObject();
                return versions;
            }
        } finally {
            conn.disconnect();
        }
    }
}
