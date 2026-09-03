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
package org.eclipse.mcp.ade.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.extension.Extension;
import org.eclipse.mcp.ade.lsp.Contributes;
import org.eclipse.mcp.ade.language.DocumentFilter;
import org.eclipse.mcp.ade.language.DocumentSelector;
import org.eclipse.mcp.ade.utils.OSUtils;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class for loading server descriptors (LSP and DAP).
 * Each server directory contains: server.json + optional installer.json.
 * Uses Gson exclusively for JSON parsing.
 *
 * @param <T> Server config type (LspServerConfig or DapServerConfig)
 */
public abstract class ServerDescriptorLoaderBase<T extends ServerConfigBase> {

    private static final Logger LOG = Logger.getLogger(ServerDescriptorLoaderBase.class);

    // Json file names
    private static final String SERVER_CONFIG_FILE = "server.json";
    private static final String INSTALLER_CONFIG_FILE = "installer.json";

    // JSON field names
    private static final String FIELD_NAME = "name";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_URL = "url";
    private static final String FIELD_DOCUMENT_SELECTOR = "documentSelector";
    private static final String FIELD_LANGUAGE = "language";
    private static final String FIELD_SCHEME = "scheme";
    private static final String FIELD_PATTERN = "pattern";
    private static final String FIELD_CONTRIBUTES = "contributes";
    private static final String FIELD_ACCEPT_CONTRIBUTIONS = "acceptContributions";
    private static final String FIELD_ENV = "env";
    private static final String FIELD_WORKING_DIRECTORY = "workingDirectory";
    private static final String FIELD_ACTIVATE_WHEN = "activateWhen";
    private static final String FIELD_FILE_EXISTS = "fileExists";
    private static final String FIELD_GLOB_PATTERN = "globPattern";
    private static final String FIELD_COMMAND = "command";
    private static final String FIELD_RUNTIME = "runtime";
    private static final String FIELD_SETTINGS = "settings";
    private static final String FIELD_REQUIRED = "required";
    protected static final Gson gson = new Gson();

    protected ServerDescriptorLoaderBase() {
    }

    /**
     * Get the root directory name for this server type (e.g., "lsp", "dap").
     */
    public abstract String getRoot();

    /**
     * Get the JSON field name for the command (e.g., "command" for LSP, "launch" for DAP).
     */
    protected abstract String getCommandFieldName();

    /**
     * Create a new instance of the config.
     */
    protected abstract T createConfig(String serverId, Extension extension);

    /**
     * Load a server configuration from a directory.
     * The directory can be a JAR entry or filesystem path.
     *
     * @param serverDir Directory containing server.json and installer.json
     * @param extension The extension this server belongs to
     * @return Loaded server configuration
     */
    public final T load(Path serverDir, Extension extension) throws IOException {
        String serverId = serverDir.getFileName().toString();

        T config = createConfig(serverId, extension);
        loadConfig(serverId, serverDir, config);
        return config;
    }

    protected void loadConfig(String serverId, Path serverDir, T config) throws IOException {
        // Load server.json
        loadServer(serverId, serverDir, config);
        // Load installer.json
        try {
            loadInstaller(serverId, serverDir, config);
            LOG.debugf("Loaded installer config for: %s", config.getServerId());
        } catch (IOException e) {
            LOG.debugf("Failed to load installer config for %s: %s", config.getServerId(), e.getMessage());
        }
    }

    /**
     * Load server configuration from server.json.
     * Parses common fields + server-specific fields.
     */
    protected JsonObject loadServer(String serverId, Path serverDir, T config) throws IOException {
        Path serverFile = serverDir.resolve(SERVER_CONFIG_FILE);
        if (!Files.exists(serverFile)) {
            throw new IOException(SERVER_CONFIG_FILE + " is required");
        }
        return loadServerFromFile(serverId, serverFile, config);
    }

    /**
     * Load server configuration from server.json.
     * Parses common fields + server-specific fields.
     */
    protected JsonObject loadServerFromFile(String serverId, Path serverFile, T config) throws IOException {
        JsonObject jsonObject = loadJson(serverFile);

        // Common fields
        config.setName(jsonObject.has(FIELD_NAME) ? jsonObject.get(FIELD_NAME).getAsString() : serverId);

        if (jsonObject.has(FIELD_DESCRIPTION)) {
            config.setDescription(jsonObject.get(FIELD_DESCRIPTION).getAsString());
        }

        if (jsonObject.has(FIELD_URL)) {
            config.setUrl(jsonObject.get(FIELD_URL).getAsString());
        }

        // Runtime dependency
        if (jsonObject.has(FIELD_RUNTIME)) {
            config.setRuntime(jsonObject.get(FIELD_RUNTIME).getAsString());
        }

        // Document selector
        if (jsonObject.has(FIELD_DOCUMENT_SELECTOR)) {
            List<DocumentFilter> filters = new ArrayList<>();
            jsonObject.getAsJsonArray(FIELD_DOCUMENT_SELECTOR).forEach(el -> {
                JsonObject filterObj = el.getAsJsonObject();
                DocumentFilter filter = new DocumentFilter();
                if (filterObj.has(FIELD_LANGUAGE)) {
                    filter.setLanguage(filterObj.get(FIELD_LANGUAGE).getAsString());
                }
                if (filterObj.has(FIELD_SCHEME)) {
                    filter.setScheme(filterObj.get(FIELD_SCHEME).getAsString());
                }
                if (filterObj.has(FIELD_PATTERN)) {
                    filter.setPattern(filterObj.get(FIELD_PATTERN).getAsString());
                }
                filters.add(filter);
            });
            config.setDocumentSelector(new DocumentSelector(filters));
        }

        // Activation condition
        if (jsonObject.has(FIELD_ACTIVATE_WHEN)) {
            JsonObject whenObj = jsonObject.getAsJsonObject(FIELD_ACTIVATE_WHEN);
            ActivationCondition condition = new ActivationCondition();
            if (whenObj.has(FIELD_FILE_EXISTS)) {
                condition.setFileExists(whenObj.get(FIELD_FILE_EXISTS).getAsString());
            }
            if (whenObj.has(FIELD_GLOB_PATTERN)) {
                condition.setGlobPattern(whenObj.get(FIELD_GLOB_PATTERN).getAsString());
            }
            if (whenObj.has(FIELD_COMMAND)) {
                JsonObject cmdObj = whenObj.getAsJsonObject(FIELD_COMMAND);
                ActivationCondition.CommandCondition cmd = new ActivationCondition.CommandCondition();
                if (cmdObj.has(FIELD_NAME)) {
                    cmd.setName(cmdObj.get(FIELD_NAME).getAsString());
                }
                condition.setCommand(cmd);
            }
            config.setActivateWhen(condition);
        }

        // Contributions
        fillContributions(config, jsonObject);

        // Accept contributions
        if (jsonObject.has(FIELD_ACCEPT_CONTRIBUTIONS)) {
            List<String> acceptContributions = new ArrayList<>();
            jsonObject.getAsJsonArray(FIELD_ACCEPT_CONTRIBUTIONS).forEach(el ->
                    acceptContributions.add(el.getAsString())
            );
            config.setAcceptContributions(acceptContributions);
        }

        // Environment variables
        if (jsonObject.has(FIELD_ENV)) {
            Map<String, String> env = new HashMap<>();
            jsonObject.getAsJsonObject(FIELD_ENV).entrySet().forEach(entry ->
                    env.put(entry.getKey(), entry.getValue().getAsString())
            );
            config.setEnv(env);
        }

        // Working directory
        if (jsonObject.has(FIELD_WORKING_DIRECTORY)) {
            config.setWorkingDirectory(jsonObject.get(FIELD_WORKING_DIRECTORY).getAsString());
        }

        // Command (resolved for current OS)
        String command = OSUtils.getStringFromOs(jsonObject, getCommandFieldName());
        if (command != null) {
            config.setCommand(command);
        }

        // Declarative settings (JSON Schema format with properties/required)
        if (jsonObject.has(FIELD_SETTINGS)) {
            config.setSettings(parseSettings(jsonObject.getAsJsonObject(FIELD_SETTINGS)));
        }

        return jsonObject;
    }

    private void fillContributions(ServerConfigBase config, JsonObject jsonObject) {
        // Contributions (LSP-specific)
        JsonElement contributesEl = jsonObject.get(FIELD_CONTRIBUTES);
        if (contributesEl != null) {
            if (contributesEl.isJsonObject()) {
                Contributes contributes = new Contributes();
                Map<String, JsonElement> contributionsMap = new HashMap<>();
                contributesEl.getAsJsonObject().entrySet().forEach(entry -> contributionsMap.put(entry.getKey(), entry.getValue()));
                contributes.setContributions(contributionsMap);
                config.setContributes(contributes);
            }
        }
    }

    protected void loadInstaller(String serverId, Path serverDir, T config) throws IOException {
        Path installerFile = serverDir.resolve(INSTALLER_CONFIG_FILE);
        if (!Files.exists(installerFile)) {
            return;
        }

        JsonObject jsonObject = loadJson(installerFile);
        config.setInstallerConfig(jsonObject);
    }

    /**
     * Parse settings in JSON Schema format:
     * <pre>
     * "settings": {
     *   "properties": {
     *     "eula": { "type": "string", "title": "...", "description": "...", "default": "" }
     *   },
     *   "required": ["eula"]
     * }
     * </pre>
     */
    private List<ServerSettingDescriptor> parseSettings(JsonObject settingsObj) {
        List<String> requiredKeys = new ArrayList<>();
        if (settingsObj.has(FIELD_REQUIRED)) {
            settingsObj.getAsJsonArray(FIELD_REQUIRED).forEach(el ->
                    requiredKeys.add(el.getAsString())
            );
        }

        List<ServerSettingDescriptor> settings = new ArrayList<>();
        JsonObject properties = settingsObj.getAsJsonObject("properties");
        if (properties == null) {
            return settings;
        }

        for (var entry : properties.entrySet()) {
            String key = entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject propObj = entry.getValue().getAsJsonObject();
            settings.add(parseSettingProperty(key, propObj, requiredKeys.contains(key)));
        }
        return settings;
    }

    private ServerSettingDescriptor parseSettingProperty(String key, JsonObject propObj, boolean required) {
        String title = propObj.has("title") ? propObj.get("title").getAsString() : key;
        String desc = propObj.has("description") ? propObj.get("description").getAsString() : null;
        String type = propObj.has("type") ? propObj.get("type").getAsString() : "string";
        String defaultValue = propObj.has("default") ? propObj.get("default").getAsString() : null;

        List<String> enumValues = null;
        if (propObj.has("enum")) {
            enumValues = new ArrayList<>();
            for (var v : propObj.getAsJsonArray("enum")) {
                enumValues.add(v.getAsString());
            }
        }

        List<String> enumDescriptions = null;
        if (propObj.has("enumDescriptions")) {
            enumDescriptions = new ArrayList<>();
            for (var v : propObj.getAsJsonArray("enumDescriptions")) {
                enumDescriptions.add(v.getAsString());
            }
        }

        return new ServerSettingDescriptor(key, title, desc, type, enumValues, enumDescriptions, defaultValue, required);
    }

    protected JsonObject loadJson(Path jsonFile) throws IOException {
        try (InputStream is = Files.newInputStream(jsonFile);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, JsonObject.class);
        }
    }

}
