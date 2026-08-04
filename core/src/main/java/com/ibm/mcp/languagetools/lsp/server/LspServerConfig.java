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

    public static class FileWatcherPattern {

        private final String globPattern;

        public FileWatcherPattern(String globPattern) {
            this.globPattern = globPattern;
        }

        public String getGlobPattern() {
            return globPattern;
        }
    }

    @Override
    public String toString() {
        return "LspServerConfig{" +
                "id='" + getServerId() + '\'' +
                ", name='" + name + '\'' +
                ", command='" + getCommand() + '\'' +
                ", documentSelector=" + getDocumentSelector() +
                '}';
    }

}
