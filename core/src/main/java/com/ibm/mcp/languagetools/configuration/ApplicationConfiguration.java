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
package com.ibm.mcp.languagetools.configuration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ibm.mcp.languagetools.PathManager;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import com.ibm.mcp.languagetools.workspace.WorkspaceConfigurationProviderRegistry;
import com.ibm.mcp.languagetools.workspace.WorkspaceConfigurationStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application settings stored in ~/.mcp-languagetools/settings.json
 * with a flat key-value format (e.g. "lsp.microprofile.trace": "messages").
 */
@ApplicationScoped
public class ApplicationConfiguration extends AbstractConfiguration {

    private static final Logger LOG = Logger.getLogger(ApplicationConfiguration.class);
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    @Inject
    PathManager pathManager;

    private final Map<String, ServerTrace> lspTraceLevels = new ConcurrentHashMap<>();
    private final Map<String, ServerTrace> dapTraceLevels = new ConcurrentHashMap<>();
    private volatile ServerTrace mcpTraceLevel;

    @PostConstruct
    void init() {
        load();
        watch();
    }

    @Override
    protected Path getSettingsFile() {
        return pathManager.getSettingsFile();
    }

    // ========== Write support ==========

    public synchronized void set(String key, String value) {
        getSettings().put(key, value);
        save();
    }

    public synchronized void setBoolean(String key, boolean value) {
        getSettings().put(key, value);
        save();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(getSettingsFile().getParent());
            String json = PRETTY_GSON.toJson(getSettings());
            Files.writeString(getSettingsFile(), json);
            LOG.infof("Saved settings to %s", getSettingsFile());
        } catch (IOException e) {
            LOG.errorf(e, "Failed to save settings to %s", getSettingsFile());
        }
    }

    // ========== Migration from old nested format ==========

    @SuppressWarnings("unchecked")
    public synchronized void migrateOldDisabledFormat() {
        Map<String, Object> settings = getSettings();
        boolean migrated = false;

        Object extensionsObj = settings.get("extensions");
        if (extensionsObj instanceof Map) {
            Map<String, Object> extMap = (Map<String, Object>) extensionsObj;
            Object disabled = extMap.get("disabled");
            if (disabled instanceof List) {
                for (Object id : (List<?>) disabled) {
                    settings.put("extension." + id + ".enabled", false);
                }
            }
            settings.remove("extensions");
            migrated = true;
        }

        Object serversObj = settings.get("servers");
        if (serversObj instanceof Map) {
            Map<String, Object> srvMap = (Map<String, Object>) serversObj;
            Object disabled = srvMap.get("disabled");
            if (disabled instanceof List) {
                for (Object id : (List<?>) disabled) {
                    settings.put("lsp." + id + ".enabled", false);
                }
            }
            settings.remove("servers");
            migrated = true;
        }

        if (migrated) {
            LOG.info("Migrated disabled state from nested to flat format in settings.json");
            save();
        }
    }

    // ========== Extensions/servers enabled state (flat format) ==========

    /**
     * Get disabled extension IDs by scanning "extension.{id}.enabled" = false entries.
     */
    public List<String> getDisabledExtensionIds() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : getSettings().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("extension.") && key.endsWith(".enabled")) {
                if (Boolean.FALSE.equals(entry.getValue())) {
                    String id = key.substring("extension.".length(), key.length() - ".enabled".length());
                    result.add(id);
                }
            }
        }
        return result;
    }

    /**
     * Get disabled server IDs by scanning "lsp.{id}.enabled" = false entries.
     */
    public List<String> getDisabledServerIds() {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Object> entry : getSettings().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("lsp.") && key.endsWith(".enabled")) {
                if (Boolean.FALSE.equals(entry.getValue())) {
                    String id = key.substring("lsp.".length(), key.length() - ".enabled".length());
                    result.add(id);
                }
            }
        }
        return result;
    }

    public synchronized void setDisabledExtensionIds(List<String> ids) {
        // Remove all existing extension.*.enabled entries
        getSettings().entrySet().removeIf(e ->
                e.getKey().startsWith("extension.") && e.getKey().endsWith(".enabled"));
        // Write only disabled ones (enabled is the default)
        for (String id : ids) {
            getSettings().put("extension." + id + ".enabled", false);
        }
        save();
    }

    public synchronized void setDisabledServerIds(List<String> ids) {
        // Remove all existing lsp.*.enabled entries
        getSettings().entrySet().removeIf(e ->
                e.getKey().startsWith("lsp.") && e.getKey().endsWith(".enabled"));
        // Write only disabled ones (enabled is the default)
        for (String id : ids) {
            getSettings().put("lsp." + id + ".enabled", false);
        }
        save();
    }

    // ========== Trace entries ==========

    public Map<String, String> getTraceLevelEntries() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getSettings().entrySet()) {
            if (entry.getKey().endsWith(".trace")) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    // ========== Workspace configuration providers ==========

    @SuppressWarnings("unchecked")
    public List<String> getWorkspaceConfigurationProviderIds() {
        Object value = get("workspace.configuration.providers");
        if (value instanceof List) {
            return (List<String>) value;
        }
        return WorkspaceConfigurationProviderRegistry.getInstance().getProviderIds();
    }

    public WorkspaceConfigurationStrategy getWorkspaceConfigurationStrategy() {
        String value = getString("workspace.configuration.strategy");
        if (value != null) {
            try {
                return WorkspaceConfigurationStrategy.valueOf(value.toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException e) {
                LOG.warnf("Unknown workspace configuration strategy: %s, using FIRST_FOUND", value);
            }
        }
        return WorkspaceConfigurationStrategy.FIRST_FOUND;
    }

    // ========== LSP trace ==========

    public ServerTrace getLspTraceLevel(String serverId) {
        return lspTraceLevels.computeIfAbsent(serverId,
                id -> ServerTrace.fromValue(getString("lsp." + id + ".trace")));
    }

    public void setLspTraceLevel(String serverId, ServerTrace level) {
        lspTraceLevels.put(serverId, level);
        set("lsp." + serverId + ".trace", level.toString());
    }

    // ========== MCP trace ==========

    public ServerTrace getMcpTraceLevel() {
        ServerTrace cached = mcpTraceLevel;
        if (cached != null) {
            return cached;
        }
        cached = ServerTrace.fromValue(getString("mcp.trace"));
        mcpTraceLevel = cached;
        return cached;
    }

    public void setMcpTraceLevel(ServerTrace level) {
        mcpTraceLevel = level;
        set("mcp.trace", level.toString());
    }

    // ========== DAP trace ==========

    public ServerTrace getDapTraceLevel(String serverId) {
        return dapTraceLevels.computeIfAbsent(serverId,
                id -> ServerTrace.fromValue(getString("dap." + id + ".trace")));
    }

    public void setDapTraceLevel(String serverId, ServerTrace level) {
        dapTraceLevels.put(serverId, level);
        set("dap." + serverId + ".trace", level.toString());
    }
}
