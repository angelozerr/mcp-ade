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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.lsp.client.LspCapability;
import com.ibm.mcp.languagetools.server.ServerConfigBase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for a language server.
 * Can be loaded from JSON or built programmatically.
 */
public class LspServerConfig extends ServerConfigBase {

    /**
     * Server initialization options
     */
    private Map<String, Object> initializationOptions = new HashMap<>();

    /**
     * Whether to skip sending didOpen before position-based requests (references, definition, etc.).
     * Defaults to false (didOpen is sent). Set to true for servers that index the whole project (e.g. JDTLS, pyright).
     */
    private boolean skipDidOpen;

    private List<FileWatcherPattern> fileWatchers;

    /**
     * Default configuration values from server.json, keyed by client-side setting name.
     * Used as fallback when IDE configuration (e.g., .vscode/settings.json) has no value.
     */
    private Map<String, Object> configuration;

    /**
     * Notification that signals the server is fully ready (e.g., after project import).
     * When set, the server is not marked ready until this notification is received.
     */
    private ReadyNotification readyNotification;

    /**
     * Notification from which to extract the server status message (e.g., import progress).
     */
    private StatusNotification statusNotification;

    public LspServerConfig(String serverId, Extension extension) {
        super(serverId, computeServerHome(serverId, extension), extension);
    }

    protected LspServerConfig(String serverId, Path serverHome, Extension extension) {
        super(serverId, serverHome, extension);
    }

    private static Path computeServerHome(String serverId, Extension extension) {
        return extension.getApplication().getPathManager()
                .getExtensionServerHome(extension.getId(), "lsp", serverId);
    }

    /**
     * Detect parent server ID from contributes configuration.
     * For contribution-only configs (like Quarkus), the parent is the server
     * they contribute classpath JARs to (e.g., microprofile).
     *
     * @return parent server ID, or null if no parent
     */
    public String getParentServerId() {
        var contributes = getContributes();
        if (contributes == null || contributes.getContributions() == null || contributes.getContributions().isEmpty()) {
            return null;
        }

        // Find the contribution with classpath - that's the parent server
        return contributes.getContributions().entrySet().stream()
            .filter(entry -> {
                var contribution = entry.getValue();
                if (!contribution.isJsonObject()) {
                    return false;
                }
                var obj = contribution.getAsJsonObject();
                return obj.has(ClasspathExtensibleContributes.CLASSPATH)
                    && obj.get(ClasspathExtensibleContributes.CLASSPATH).isJsonArray();
            })
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    // Getters and setters (id, name, description, command, env, workingDirectory, installer inherited from ServerConfigBase)

    public Map<String, Object> getInitializationOptions() {
        return initializationOptions;
    }

    public void setInitializationOptions(Map<String, Object> initializationOptions) {
        this.initializationOptions = initializationOptions;
    }

    public boolean isSkipDidOpen(LspCapability capability) {
        return skipDidOpen;
    }

    public void setSkipDidOpen(boolean skipDidOpen) {
        this.skipDidOpen = skipDidOpen;
    }

    public List<FileWatcherPattern> getFileWatchers() {
        return fileWatchers;
    }

    public void setFileWatchers(List<FileWatcherPattern> fileWatchers) {
        this.fileWatchers = fileWatchers;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
    }

    /**
     * Returns the ready notification descriptor, or {@code null} if the server
     * is considered ready immediately after the LSP {@code initialize} handshake.
     */
    public ReadyNotification getReadyNotification() {
        return readyNotification;
    }

    /**
     * Sets the ready notification descriptor.
     *
     * @param readyNotification the notification that signals server readiness,
     *                          or {@code null} to mark the server ready on {@code initialize}
     */
    public void setReadyNotification(ReadyNotification readyNotification) {
        this.readyNotification = readyNotification;
    }

    /**
     * Returns the status notification descriptor, or {@code null} if the server
     * does not report progress via a custom notification.
     */
    public StatusNotification getStatusNotification() {
        return statusNotification;
    }

    /**
     * Sets the status notification descriptor.
     *
     * @param statusNotification the notification from which to extract the server
     *                           status message, or {@code null} to disable
     */
    public void setStatusNotification(StatusNotification statusNotification) {
        this.statusNotification = statusNotification;
    }

    public static class FileWatcherPattern {

        private final String globPattern;

        public FileWatcherPattern(String globPattern) {
            this.globPattern = globPattern;
        }

        public String getGlobPattern() {
            return globPattern;
        }
    }

    private static final Gson GSON = new Gson();

    static JsonObject toJsonObject(Object params) {
        if (params instanceof JsonObject jo) {
            return jo;
        }
        if (params instanceof JsonElement je) {
            return je.isJsonObject() ? je.getAsJsonObject() : null;
        }
        JsonElement tree = GSON.toJsonTree(params);
        return tree.isJsonObject() ? tree.getAsJsonObject() : null;
    }

    static JsonElement resolveField(JsonObject obj, String path) {
        String[] parts = path.split("\\.");
        JsonElement current = obj;
        for (String part : parts) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }

    /**
     * Describes a notification the server sends when it is fully ready.
     *
     * <p>Declared in server.json as either:
     * <ul>
     *   <li>A string (method only): {@code "readyNotification": "intellij/ready-for-test"}</li>
     *   <li>An object (method + param matching): {@code "readyNotification": { "language/status": { "type": "ServiceReady" } }}</li>
     * </ul>
     *
     * <p>Match keys support dot notation for nested fields (e.g. {@code "status.state"}).
     */
    public static class ReadyNotification {

        private final String method;
        private final Map<String, String> match;

        public ReadyNotification(String method) {
            this(method, null);
        }

        public ReadyNotification(String method, Map<String, String> match) {
            this.method = method;
            this.match = match;
        }

        public String getMethod() {
            return method;
        }

        public Map<String, String> getMatch() {
            return match;
        }

        public boolean matches(String notificationMethod, Object params) {
            if (!method.equals(notificationMethod)) {
                return false;
            }
            if (match == null || match.isEmpty()) {
                return true;
            }
            if (params == null) {
                return false;
            }
            JsonObject obj = toJsonObject(params);
            if (obj == null) {
                return false;
            }
            for (var entry : match.entrySet()) {
                JsonElement el = resolveField(obj, entry.getKey());
                if (el == null || !el.isJsonPrimitive() || !entry.getValue().equals(el.getAsString())) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Describes a notification from which to extract the server status message.
     *
     * <p>Declared in server.json as:
     * {@code "statusNotification": { "language/status": "message" }}
     *
     * <p>The key is the notification method, the value is the field path
     * (dot notation supported) to extract the message text from the params.
     */
    public static class StatusNotification {

        private final String method;
        private final String fieldPath;

        public StatusNotification(String method, String fieldPath) {
            this.method = method;
            this.fieldPath = fieldPath;
        }

        public String getMethod() {
            return method;
        }

        public String getFieldPath() {
            return fieldPath;
        }

        public String extractMessage(String notificationMethod, Object params) {
            if (!method.equals(notificationMethod)) {
                return null;
            }
            if (params == null) {
                return null;
            }
            JsonObject obj = toJsonObject(params);
            if (obj == null) {
                return null;
            }
            JsonElement el = resolveField(obj, fieldPath);
            if (el == null || !el.isJsonPrimitive()) {
                return null;
            }
            return el.getAsString();
        }
    }

    @Override
    public String toString() {
        return "LspServerConfig{" +
                "id='" + getServerId() + '\'' +
                ", name='" + getName() + '\'' +
                ", command='" + getCommand() + '\'' +
                ", documentSelector=" + getDocumentSelector() +
                '}';
    }

}
