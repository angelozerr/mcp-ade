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
import com.google.gson.JsonParser;
import org.eclipse.mcp.ade.utils.OSUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Function;

/**
 * Fetches the download URL of a VS Code extension from the Open VSX registry.
 *
 * <p>Queries the Open VSX API at {@code https://open-vsx.org/api/{namespace}/{extensionName}}
 * and extracts the VSIX download URL from the {@code files.download} field.</p>
 *
 * <p>When {@code targetPlatform} is {@code true}, appends the current platform
 * (e.g. {@code win32-x64}, {@code linux-arm64}) to the API URL to fetch a
 * platform-specific VSIX.</p>
 */
public class OpenVsxAssetFetcher implements AssetFetcher {

    private final String namespace;
    private final String extensionName;
    private final boolean targetPlatform;
    private JsonObject extensionInfo;

    public OpenVsxAssetFetcher(String namespace, String extensionName, boolean targetPlatform) {
        this.namespace = namespace;
        this.extensionName = extensionName;
        this.targetPlatform = targetPlatform;
    }

    @Override
    public String getDownloadUrl(Function<JsonObject, Boolean> releaseMatcher,
                                 Function<JsonObject, Boolean> assetMatcher,
                                 Reporter reporter) {
        try {
            String vsixUrl = getVsixDownloadUrl(reporter);
            if (vsixUrl == null) {
                return null;
            }
            return resolveDownloadUrl(vsixUrl, reporter);
        } catch (Exception e) {
            reporter.setText("Error while searching Open VSX asset", e);
        }
        return null;
    }

    /**
     * Returns the VSIX download URL from the Open VSX API.
     */
    protected String getVsixDownloadUrl(Reporter reporter) throws Exception {
        JsonObject info = getOrLoadExtensionInfo(reporter);
        if (info == null) {
            return null;
        }
        reporter.setText("> Searching Open VSX asset to download...");

        JsonObject files = info.getAsJsonObject("files");
        if (files == null || !files.has("download")) {
            reporter.setText("No download URL found in Open VSX response");
            return null;
        }

        String downloadUrl = files.get("download").getAsString();
        String version = info.has("version") ? info.get("version").getAsString() : "unknown";
        reporter.setText("Asset found: " + extensionName + " v" + version + " - " + downloadUrl);
        return downloadUrl;
    }

    /**
     * Resolves the final download URL from the VSIX URL.
     * Subclasses can override to perform additional resolution
     * (e.g. downloading the VSIX to extract a server bundle URL).
     */
    protected String resolveDownloadUrl(String vsixUrl, Reporter reporter) {
        return vsixUrl;
    }

    /**
     * Returns the latest version string, or null if not loaded.
     */
    public String getVersion() {
        if (extensionInfo != null && extensionInfo.has("version")) {
            return extensionInfo.get("version").getAsString();
        }
        return null;
    }

    private JsonObject getOrLoadExtensionInfo(Reporter reporter) throws Exception {
        if (extensionInfo != null) {
            return extensionInfo;
        }
        String json = fetchExtensionJson(reporter);
        if (json == null) {
            return null;
        }
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            reporter.setText("Invalid Open VSX response: not a JSON object");
            return null;
        }
        return extensionInfo = parsed.getAsJsonObject();
    }

    private String fetchExtensionJson(Reporter reporter) {
        String urlStr = "https://open-vsx.org/api/" + namespace + "/" + extensionName;
        if (targetPlatform) {
            urlStr += "/" + getOpenVsxTargetPlatform();
        }
        reporter.setText("> Loading Open VSX extension info: " + urlStr);

        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new RuntimeException("Open VSX API returned HTTP " + responseCode);
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
        } catch (Exception e) {
            reporter.setText("Error while loading Open VSX extension info: ", e);
        }
        return null;
    }

    static String getOpenVsxTargetPlatform() {
        String os;
        if (OSUtils.isWindows()) {
            os = "win32";
        } else if (OSUtils.isMac()) {
            os = "darwin";
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
}
