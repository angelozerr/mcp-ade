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
package com.ibm.mcp.languagetools;

import com.ibm.mcp.languagetools.configuration.PathConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;

/**
 * Centralized path management for MCP Language Tools.
 * Handles all path resolution and variable substitution.
 * Uses PathConfig for configurable paths.
 */
@ApplicationScoped
public class PathManager {

    // File names (hardcoded constants)
    public static final String SETTINGS_JSON = "settings.json";

    @Inject
    PathConfig pathConfig;

    /**
     * Get the root directory for MCP Language Tools (~/.mcp-languagetools by default)
     */
    public Path getMcpLangToolsRoot() {
        return pathConfig.getMcpLangToolsDir();
    }

    // ----------------------- LSP configuration

    /**
     * Get the directory where LSP servers are installed (~/.mcp-languagetools/lsp)
     */
    public Path getLspServersDir() {
        return getMcpLangToolsRoot().resolve(PathConfig.getLspDirName());
    }

    /**
     * Get the home directory for a specific LSP server (~/.mcp-languagetools/lsp/{serverId})
     */
    public Path getLspServerHome(String serverId) {
        return getLspServersDir().resolve(serverId);
    }

    /**
     * Get the config directory for LSP servers (~/.mcp-languagetools/config/lsp)
     */
    public Path getLspConfigDir() {
        return getConfigDir().resolve(PathConfig.getLspDirName());
    }

    /**
     * Get the config directory for a specific LSP server (~/.mcp-languagetools/config/lsp/{serverId})
     */
    public Path getLspConfigDir(String serverId) {
        return getLspConfigDir().resolve(serverId);
    }

    // ----------------------- DAP configuration

    /**
     * Get the directory where DAP servers are installed (~/.mcp-languagetools/dap)
     */
    public Path getDapServersDir() {
        return getMcpLangToolsRoot().resolve(PathConfig.getDapDirName());
    }

    /**
     * Get the home directory for a specific DAP server (~/.mcp-languagetools/dap/{serverId})
     */
    public Path getDapServerHome(String serverId) {
        return getDapServersDir().resolve(serverId);
    }

    // ----------------------- Runtimes

    private static final String DIR_RUNTIMES = "runtime";

    /**
     * Get the directory where runtimes are installed (~/.mcp-languagetools/runtime)
     */
    public Path getRuntimesDir() {
        return getMcpLangToolsRoot().resolve(DIR_RUNTIMES);
    }

    /**
     * Get the home directory for a specific runtime (~/.mcp-languagetools/runtime/{runtimeId})
     */
    public Path getRuntimeHome(String runtimeId) {
        return getRuntimesDir().resolve(runtimeId);
    }

    // ----------------------- Extensions

    private static final String DIR_EXTENSIONS = "extensions";

    /**
     * Get the extensions directory (~/.mcp-languagetools/extensions)
     */
    public Path getExtensionsDir() {
        return getMcpLangToolsRoot().resolve(DIR_EXTENSIONS);
    }

    /**
     * Get the directory for a specific extension (~/.mcp-languagetools/extensions/{extensionId})
     */
    public Path getExtensionDir(String extensionId) {
        return getExtensionsDir().resolve(extensionId);
    }

    /**
     * Get the server home within an extension.
     * (~/.mcp-languagetools/extensions/{extensionId}/{type}/{serverId})
     *
     * @param extensionId the extension id
     * @param type        "lsp" or "dap"
     * @param serverId    the server id
     */
    public Path getExtensionServerHome(String extensionId, String type, String serverId) {
        return getExtensionDir(extensionId).resolve(type).resolve(serverId);
    }

    // Workspace configuration

    /**
     * Get the config directory root (~/.mcp-languagetools/config)
     */
    public Path getConfigDir() {
        return getMcpLangToolsRoot().resolve(pathConfig.getConfigDirName());
    }

    /**
     * Get the settings file path (~/.mcp-languagetools/settings.json)
     */
    public Path getSettingsFile() {
        return getMcpLangToolsRoot().resolve(SETTINGS_JSON);
    }

}
