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
import com.google.gson.reflect.TypeToken;
import org.jboss.logging.Logger;

import org.eclipse.lsp4j.ConfigurationItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class for JSON-based configuration files (application settings, workspace settings).
 * Loads a settings.json file into a Map and provides typed accessors with dot-notation support.
 */
public abstract class AbstractConfiguration implements Configuration {

    private static final Logger LOG = Logger.getLogger(AbstractConfiguration.class);
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    private volatile Map<String, Object> settings = new ConcurrentHashMap<>();

    /**
     * Return the path to the JSON settings file to load.
     */
    protected abstract Path getSettingsFile();

    /**
     * Load settings from the JSON file returned by {@link #getSettingsFile()}.
     */
    protected void load() {
        Path settingsFile = getSettingsFile();
        settings = loadFromFile(settingsFile);
    }

    @Override
    public void reload() {
        LOG.infof("Reloading configuration from: %s", getSettingsFile());
        load();
    }

    private FileWatcher fileWatcher;

    @Override
    public void watch() {
        if (fileWatcher != null) {
            fileWatcher.stop();
        }
        Path settingsFile = getSettingsFile();
        if (settingsFile != null) {
            fileWatcher = new FileWatcher(settingsFile, this::reload);
            fileWatcher.start();
        }
    }

    @Override
    public void watchWith(FileWatcher sharedWatcher) {
        Path settingsFile = getSettingsFile();
        if (settingsFile != null) {
            sharedWatcher.watchFile(settingsFile, this::reload);
        }
    }

    @Override
    public void unwatch() {
        if (fileWatcher != null) {
            fileWatcher.stop();
            fileWatcher = null;
        }
    }

    /**
     * Load settings from a specific JSON file.
     *
     * @param file the settings file to load (may be null or non-existent)
     * @return the loaded settings map, or an empty map if the file is missing or invalid
     */
    protected Map<String, Object> loadFromFile(Path file) {
        if (file == null || !Files.exists(file)) {
            LOG.debugf("No settings file found at: %s", file);
            return new ConcurrentHashMap<>();
        }

        try {
            String json = Files.readString(file);
            json = stripJsonComments(json);
            TypeToken<Map<String, Object>> typeToken = new TypeToken<>() {
            };
            Map<String, Object> loaded = GSON.fromJson(json, typeToken.getType());
            LOG.infof("Loaded settings from %s", file);
            return loaded != null ? new ConcurrentHashMap<>(loaded) : new ConcurrentHashMap<>();
        } catch (Exception e) {
            LOG.warnf("Failed to load settings from %s: %s", file, e.getMessage());
            return new ConcurrentHashMap<>();
        }
    }

    /**
     * Strip JSONC features (single-line comments, block comments, trailing commas)
     * to produce strict JSON that Gson can parse.
     */
    static String stripJsonComments(String jsonc) {
        StringBuilder sb = new StringBuilder(jsonc.length());
        int i = 0;
        int len = jsonc.length();
        while (i < len) {
            char c = jsonc.charAt(i);
            if (c == '"') {
                // Copy quoted string as-is (skip escaped quotes)
                sb.append(c);
                i++;
                while (i < len) {
                    char sc = jsonc.charAt(i);
                    sb.append(sc);
                    if (sc == '\\' && i + 1 < len) {
                        i++;
                        sb.append(jsonc.charAt(i));
                    } else if (sc == '"') {
                        break;
                    }
                    i++;
                }
                i++;
            } else if (c == '/' && i + 1 < len && jsonc.charAt(i + 1) == '/') {
                // Single-line comment: skip until end of line
                i += 2;
                while (i < len && jsonc.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < len && jsonc.charAt(i + 1) == '*') {
                // Block comment: skip until */
                i += 2;
                while (i + 1 < len && !(jsonc.charAt(i) == '*' && jsonc.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        // Remove trailing commas before } or ]
        return sb.toString().replaceAll(",\\s*([}\\]])", "$1");
    }

    /**
     * Return the internal settings map.
     */
    protected Map<String, Object> getSettings() {
        return settings;
    }

    // ========== Accessors ==========

    public Object get(String key) {
        return get(key, null);
    }

    public Object get(String key, Object defaultValue) {
        Object value = getNestedValue(settings, key);
        return value != null ? value : defaultValue;
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public String getString(String key, String defaultValue) {
        Object value = get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return value != null ? String.valueOf(value) : defaultValue;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        Object value = get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    public Map<String, Object> getAll() {
        return new HashMap<>(settings);
    }

    // ========== Write support ==========

    public synchronized void set(String key, Object value) {
        settings.put(key, value);
        save();
    }

    public synchronized void setBoolean(String key, boolean value) {
        settings.put(key, value);
        save();
    }

    public synchronized void remove(String key) {
        settings.remove(key);
        save();
    }

    public synchronized void save() {
        Path file = getSettingsFile();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            String json = PRETTY_GSON.toJson(settings);
            Files.writeString(file, json);
            LOG.infof("Saved configuration to %s", file);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to save configuration to %s", file);
        }
    }

    // ========== Trace levels ==========

    public ServerTrace getLspTraceLevel(String serverId) {
        return ServerTrace.fromValue(getString("lsp." + serverId + ".trace"));
    }

    public void setLspTraceLevel(String serverId, ServerTrace level) {
        set("lsp." + serverId + ".trace", level.toString());
    }

    public ServerTrace getDapTraceLevel(String serverId) {
        return ServerTrace.fromValue(getString("dap." + serverId + ".trace"));
    }

    public void setDapTraceLevel(String serverId, ServerTrace level) {
        set("dap." + serverId + ".trace", level.toString());
    }

    public ServerTrace getBspTraceLevel(String serverId) {
        return ServerTrace.fromValue(getString("bsp." + serverId + ".trace"));
    }

    public void setBspTraceLevel(String serverId, ServerTrace level) {
        set("bsp." + serverId + ".trace", level.toString());
    }

    // ========== Section-based find ==========

    /**
     * Find settings for the given LSP configuration item.
     * Resolves the item's section using three modes:
     * <ul>
     *   <li>Direct key match: {@code "mylsp"} returns the value at that key</li>
     *   <li>Dot-notation traversal: {@code "mylsp.subsetting"} traverses nested maps</li>
     *   <li>Flat key prefix: {@code "flat.scalar"} matches keys like {@code "flat.scalar.value"}</li>
     * </ul>
     *
     * @param item the LSP configuration item (may be null)
     * @return the matching value, or null if not found
     */
    @Override
    public Object find(ConfigurationItem item) {
        String section = item != null ? item.getSection() : null;
        if (section == null) {
            return null;
        }

        // Direct key match
        if (settings.containsKey(section)) {
            return settings.get(section);
        }

        // Dot-notation traversal into nested maps
        String[] sections = section.split("\\.");
        boolean found = false;
        Object current = settings;
        for (String part : sections) {
            if (current instanceof Map<?, ?> currentMap && currentMap.containsKey(part)) {
                current = currentMap.get(part);
                found = true;
            } else {
                found = false;
                break;
            }
        }

        if (found) {
            return current;
        }

        // Flat key prefix matching: filter keys that start with the section path
        Map<String, Object> matched = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : Set.copyOf(settings.entrySet())) {
            String key = entry.getKey();
            String[] keySplit = key.split("\\.");
            if (sections.length > keySplit.length) {
                continue;
            }
            boolean prefixMatch = true;
            for (int i = 0; i < sections.length; i++) {
                if (!sections[i].equals(keySplit[i])) {
                    prefixMatch = false;
                    break;
                }
            }
            if (prefixMatch) {
                matched.put(key, entry.getValue());
            }
        }
        return matched.isEmpty() ? null : matched;
    }

    @Override
    public List<Object> find(List<ConfigurationItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .map(this::find)
                .toList();
    }

    // ========== Nested key resolution ==========

    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> map, String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        if (map.containsKey(key)) {
            return map.get(key);
        }

        String[] parts = key.split("\\.", 2);
        if (parts.length == 1) {
            return map.get(key);
        }

        Object current = map.get(parts[0]);
        if (current instanceof Map) {
            return getNestedValue((Map<String, Object>) current, parts[1]);
        }

        return null;
    }
}
