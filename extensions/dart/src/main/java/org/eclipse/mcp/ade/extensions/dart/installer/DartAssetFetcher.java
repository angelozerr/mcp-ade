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
package org.eclipse.mcp.ade.extensions.dart.installer;

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
 * Asset fetcher for Dart SDK binaries using the official Dart archive.
 * <p>
 * Dart distributes binaries via {@code https://storage.googleapis.com/dart-archive/}.
 * This fetcher queries the latest stable version and constructs the download URL
 * for the current OS and architecture.
 */
public class DartAssetFetcher implements AssetFetcher {

    private static final String VERSION_URL = "https://storage.googleapis.com/dart-archive/channels/stable/release/latest/VERSION";
    private static final String DOWNLOAD_BASE_URL = "https://storage.googleapis.com/dart-archive/channels/stable/release/";

    private String cachedVersion;

    @Override
    public String getDownloadUrl(Function<JsonObject, Boolean> releaseMatcher,
                                 Function<JsonObject, Boolean> assetMatcher,
                                 Reporter reporter) {
        try {
            String version = getOrFetchVersion(reporter);
            if (version == null) {
                return null;
            }

            reporter.setText("> Found Dart SDK " + version);

            String dartOs = getDartOs();
            String dartArch = getDartArch();
            String filename = "dartsdk-" + dartOs + "-" + dartArch + "-release.zip";
            String url = DOWNLOAD_BASE_URL + version + "/sdk/" + filename;

            reporter.setText("Dart SDK download URL: " + url);
            return url;
        } catch (Exception e) {
            reporter.setText("Error fetching Dart SDK release info", e);
        }
        return null;
    }

    private static String getDartOs() {
        if (OSUtils.isWindows()) {
            return "windows";
        } else if (OSUtils.isMac()) {
            return "macos";
        } else {
            return "linux";
        }
    }

    private static String getDartArch() {
        if ("arm64".equals(OSUtils.ARCH_KEY)) {
            return "arm64";
        }
        return "x64";
    }

    private String getOrFetchVersion(Reporter reporter) throws Exception {
        if (cachedVersion != null) {
            return cachedVersion;
        }
        reporter.setText("> Fetching latest stable Dart SDK version...");

        URL url = new URL(VERSION_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                reporter.setText("HTTP " + responseCode + " from " + VERSION_URL);
                return null;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                JsonObject versionInfo = JsonParser.parseString(sb.toString()).getAsJsonObject();
                cachedVersion = versionInfo.get("version").getAsString();
                return cachedVersion;
            }
        } finally {
            conn.disconnect();
        }
    }
}
