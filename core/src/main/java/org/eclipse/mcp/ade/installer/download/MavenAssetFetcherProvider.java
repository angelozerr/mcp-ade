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

/**
 * {@link AssetFetcherProvider} for Maven Central artifacts.
 * <p>
 * Recognizes the {@code "maven"} JSON property with {@code "groupId"} and {@code "artifactId"} fields.
 */
public class MavenAssetFetcherProvider implements AssetFetcherProvider {

    private static final String MAVEN_JSON_PROPERTY = "maven";
    private static final String MAVEN_GROUP_ID_JSON_PROPERTY = "groupId";
    private static final String MAVEN_ARTIFACT_ID_JSON_PROPERTY = "artifactId";

    @Override
    public AssetFetcherInfo getAssetFetcher(JsonObject downloadJson) {
        if (!downloadJson.has(MAVEN_JSON_PROPERTY)) {
            return null;
        }
        JsonElement mavenElement = downloadJson.get(MAVEN_JSON_PROPERTY);
        if (!mavenElement.isJsonObject()) {
            return null;
        }
        JsonObject mavenObj = mavenElement.getAsJsonObject();
        if (!mavenObj.has(MAVEN_GROUP_ID_JSON_PROPERTY) || !mavenObj.has(MAVEN_ARTIFACT_ID_JSON_PROPERTY)) {
            return null;
        }
        String groupId = mavenObj.get(MAVEN_GROUP_ID_JSON_PROPERTY).getAsString();
        String artifactId = mavenObj.get(MAVEN_ARTIFACT_ID_JSON_PROPERTY).getAsString();

        var assetFetcher = MavenArtifactFetcherManager.getInstance().getArtifactFetcher(groupId, artifactId);
        return new AssetFetcherInfo(assetFetcher,
                obj -> true,
                obj -> true);
    }
}
