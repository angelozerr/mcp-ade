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
package com.ibm.mcp.languagetools.runtime;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.extension.Extension;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads runtime descriptors from runtime/ directories within extensions.
 * Each runtime directory contains an installer.json file.
 */
public class RuntimeDescriptorLoader {

    private static final Logger LOG = Logger.getLogger(RuntimeDescriptorLoader.class);

    private static final String INSTALLER_CONFIG_FILE = "installer.json";
    private static final Gson gson = new Gson();

    public static final String ROOT = "runtime";

    /**
     * Load a runtime configuration from a directory.
     *
     * @param runtimeDir directory containing installer.json
     * @param runtimeHome installation directory for this runtime
     * @param extension the extension this runtime belongs to
     * @return loaded runtime configuration
     */
    public RuntimeConfig load(Path runtimeDir, Path runtimeHome, Extension extension) throws IOException {
        String runtimeId = runtimeDir.getFileName().toString();
        RuntimeConfig config = new RuntimeConfig(runtimeId, runtimeHome, extension);

        Path installerFile = runtimeDir.resolve(INSTALLER_CONFIG_FILE);
        if (!Files.exists(installerFile)) {
            throw new IOException("installer.json is required for runtime: " + runtimeId);
        }

        JsonObject jsonObject = loadJson(installerFile);

        if (jsonObject.has("name")) {
            config.setName(jsonObject.get("name").getAsString());
        } else {
            config.setName(runtimeId);
        }

        if (jsonObject.has("description")) {
            config.setDescription(jsonObject.get("description").getAsString());
        }

        if (jsonObject.has("url")) {
            config.setUrl(jsonObject.get("url").getAsString());
        }

        config.setInstallerConfig(jsonObject);

        LOG.infof("Loaded runtime: %s (%s)", runtimeId, config.getName());
        return config;
    }

    private JsonObject loadJson(Path jsonFile) throws IOException {
        try (InputStream is = Files.newInputStream(jsonFile);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, JsonObject.class);
        }
    }
}
