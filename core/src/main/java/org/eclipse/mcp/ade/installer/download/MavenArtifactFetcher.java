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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Utility class to fetch the download URL of a Maven artifact from Maven Central.
 */
public class MavenArtifactFetcher implements AssetFetcher {

    private final String groupId;
    private final String artifactId;
    private JsonArray docs;

    public MavenArtifactFetcher(String groupId, String artifactId) {
        this.groupId = groupId;
        this.artifactId = artifactId;
    }

    /**
     * Finds the download URL for an asset in the latest release matching the given release filter
     * and asset filter.
     *
     * @param releaseMatcher the filter to select the desired release (e.g. prerelease or not)
     * @param docMatcher     the filter to select the desired asset within the release
     * @param reporter       an object to report progress or status messages
     * @return the download URL of the matching asset, or null if none found
     */
    @Override
    public String getDownloadUrl(Function<JsonObject, Boolean> releaseMatcher,
                                           Function<JsonObject, Boolean> docMatcher,
                                           Reporter reporter) {
        try {
            JsonArray docs = getOrLoadDocs(reporter);
            if (docs == null) {
                return null;
            }
            reporter.setText("> Searching Maven artifact to download....");
            for (JsonElement docElem : docs) {
                JsonObject doc = docElem.getAsJsonObject();
                if (docMatcher.apply(doc)) {
                    String version = doc.get("latestVersion").getAsString();
                    String groupPath = groupId.replace('.', '/');
                    String url = "https://repo1.maven.org/maven2/" + groupPath + "/" + artifactId + "/" + version + "/" +
                            artifactId + "-" + version + ".jar";
                    reporter.setText("Artifact found " + url);
                    return url;
                }
            }
            reporter.setText("No matching artifact found....");
        } catch (Exception e) {
            reporter.setText("Error while searching Maven artifacts: ", e);
        }
        return null;
    }

    private JsonArray getOrLoadDocs(Reporter reporter) throws Exception {
        if (docs != null) {
            return docs;
        }
        String json = fetchDocsFromMavenCentral(reporter);
        if (json == null) {
            return null;
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return docs = root.getAsJsonObject("response").getAsJsonArray("docs");
    }

    private String fetchDocsFromMavenCentral(Reporter reporter) {
        String query = String.format("g:\"%s\" AND a:\"%s\"", groupId, artifactId);
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String urlStr = "https://search.maven.org/solrsearch/select?q=" + encodedQuery + "&rows=20&wt=json";
        reporter.setText("> Loading Maven docs: " + urlStr);

        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("GET");
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new RuntimeException("Maven Central API returned HTTP " + responseCode);
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
            reporter.setText("Error while loading Maven docs: ", e);
        }
        return null;
    }
}
