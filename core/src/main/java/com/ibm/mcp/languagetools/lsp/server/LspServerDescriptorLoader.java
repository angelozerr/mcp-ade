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
package com.ibm.mcp.languagetools.lsp.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.server.ServerDescriptorLoaderBase;
import com.ibm.mcp.languagetools.configuration.PathConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads LSP server descriptors from JSON files.
 * Handles both server.json and server-extension.json.
 * Uses Gson exclusively for JSON parsing.
 */
@ApplicationScoped
public class LspServerDescriptorLoader extends ServerDescriptorLoaderBase<LspServerConfig> {

    // JSON field names
    private static final String FIELD_INITIALIZATION_OPTIONS = "initializationOptions";
    private static final String FIELD_CONFIGURATION = "configuration";
    private static final String FIELD_TOOLS_REQUEST = "toolsRequest";
    private static final String FIELD_SKIP_DID_OPEN = "skipDidOpen";
    private static final String FIELD_FILE_WATCHERS = "fileWatchers";
    private static final String FIELD_GLOB_PATTERN = "globPattern";
    private static final String FIELD_READY_NOTIFICATION = "readyNotification";
    private static final String FIELD_STATUS_NOTIFICATION = "statusNotification";

    @Override
    public String getRoot() {
        return PathConfig.getLspDirName();
    }

    @Override
    protected String getCommandFieldName() {
        return "command";
    }

    @Override
    protected LspServerConfig createConfig(String serverId, Extension extension) {
        return new LspServerConfig(serverId, extension);
    }

    @Override
    protected JsonObject loadServer(String serverId, Path serverDir, LspServerConfig config) throws IOException {
        // server.json
        JsonObject jsonObject = super.loadServer(serverId, serverDir, config);

        // Initialization options
        if (jsonObject.has(FIELD_INITIALIZATION_OPTIONS)) {
            Map<String, Object> initOptions = gson.fromJson(
                    jsonObject.get(FIELD_INITIALIZATION_OPTIONS),
                    Map.class
            );
            config.setInitializationOptions(initOptions);
        }

        // Configuration defaults (used as fallback for workspace/configuration)
        if (jsonObject.has(FIELD_CONFIGURATION)) {
            Map<String, Object> configuration = gson.fromJson(
                    jsonObject.get(FIELD_CONFIGURATION),
                    Map.class
            );
            config.setConfiguration(configuration);
        }

        // Tools request settings
        if (jsonObject.has(FIELD_TOOLS_REQUEST)) {
            JsonObject toolsRequest = jsonObject.getAsJsonObject(FIELD_TOOLS_REQUEST);
            if (toolsRequest.has(FIELD_SKIP_DID_OPEN)) {
                config.setSkipDidOpen(toolsRequest.get(FIELD_SKIP_DID_OPEN).getAsBoolean());
            }
        }

        // Ready notification
        if (jsonObject.has(FIELD_READY_NOTIFICATION)) {
            JsonElement rnElement = jsonObject.get(FIELD_READY_NOTIFICATION);
            if (rnElement.isJsonPrimitive()) {
                config.setReadyNotification(
                        new LspServerConfig.ReadyNotification(rnElement.getAsString()));
            } else if (rnElement.isJsonObject()) {
                JsonObject rnObj = rnElement.getAsJsonObject();
                var entry = rnObj.entrySet().iterator().next();
                String method = entry.getKey();
                Map<String, String> match = new HashMap<>();
                if (entry.getValue().isJsonObject()) {
                    entry.getValue().getAsJsonObject().entrySet().forEach(e ->
                            match.put(e.getKey(), e.getValue().getAsString()));
                }
                config.setReadyNotification(
                        new LspServerConfig.ReadyNotification(method, match));
            }
        }

        // Status notification
        if (jsonObject.has(FIELD_STATUS_NOTIFICATION)) {
            JsonObject snObj = jsonObject.getAsJsonObject(FIELD_STATUS_NOTIFICATION);
            var entry = snObj.entrySet().iterator().next();
            config.setStatusNotification(
                    new LspServerConfig.StatusNotification(entry.getKey(), entry.getValue().getAsString()));
        }

        // Static file watcher patterns
        if (jsonObject.has(FIELD_FILE_WATCHERS)) {
            List<LspServerConfig.FileWatcherPattern> patterns = new ArrayList<>();
            jsonObject.getAsJsonArray(FIELD_FILE_WATCHERS).forEach(el -> {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has(FIELD_GLOB_PATTERN)) {
                    patterns.add(new LspServerConfig.FileWatcherPattern(
                            obj.get(FIELD_GLOB_PATTERN).getAsString()));
                }
            });
            config.setFileWatchers(patterns);
        }

        return jsonObject;
    }

}
