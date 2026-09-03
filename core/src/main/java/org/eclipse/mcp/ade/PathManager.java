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
package org.eclipse.mcp.ade;

import org.eclipse.mcp.ade.configuration.PathConfig;
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
     * Get the root directory for MCP ADE (~/.mcp-ade by default)
     */
    public Path getMcpAdeRoot() {
        return pathConfig.getMcpAdeDir();
    }

    // ----------------------- LSP configuration

    /**
     * Get the directory where LSP servers are installed (~/.mcp-ade/lsp)
     */
    public Path getLspServersDir() {
        return getMcpAdeRoot().resolve(PathConfig.getLspDirName());
    }

    /**
     * Get the home directory for a specific LSP server (~/.mcp-ade/lsp/{serverId})
     */
    public Path getLspServerHome(String serverId) {
        return getLspServersDir().resolve(serverId);
    }

    /**
     * Get the config directory for LSP servers (~/.mcp-ade/config/lsp)
     */
    public Path getLspConfigDir() {
        return getConfigDir().resolve(PathConfig.getLspDirName());
    }

    /**
     * Get the config directory for a specific LSP server (~/.mcp-ade/config/lsp/{serverId})
     */
    public Path getLspConfigDir(String serverId) {
        return getLspConfigDir().resolve(serverId);
    }

    // ----------------------- DAP configuration

    /**
     * Get the directory where DAP servers are installed (~/.mcp-ade/dap)
     */
    public Path getDapServersDir() {
        return getMcpAdeRoot().resolve(PathConfig.getDapDirName());
    }

    /**
     * Get the home directory for a specific DAP server (~/.mcp-ade/dap/{serverId})
     */
    public Path getDapServerHome(String serverId) {
        return getDapServersDir().resolve(serverId);
    }

    // ----------------------- Workspace storage

    /**
     * Get the workspace storage root directory (~/.mcp-ade/workspace-storage)
     */
    public Path getWorkspaceStorageDir() {
        return getMcpAdeRoot().resolve(PathConfig.getWorkspaceStorageDirName());
    }

    /**
     * Get the workspace storage directory for a specific server (~/.mcp-ade/workspace-storage/{serverId})
     */
    public Path getWorkspaceStorageDir(String serverId) {
        return getWorkspaceStorageDir().resolve(serverId);
    }

    // ----------------------- Runtimes

    /**
     * Get the directory where runtimes are installed (~/.mcp-ade/runtimes)
     */
    public Path getRuntimesDir() {
        return getMcpAdeRoot().resolve(PathConfig.getRuntimesDirName());
    }

    /**
     * Get the home directory for a specific runtime (~/.mcp-ade/runtime/{runtimeId})
     */
    public Path getRuntimeHome(String runtimeId) {
        return getRuntimesDir().resolve(runtimeId);
    }

    // ----------------------- Extensions

    /**
     * Get the extensions directory (~/.mcp-ade/extensions)
     */
    public Path getExtensionsDir() {
        return getMcpAdeRoot().resolve(PathConfig.getExtensionsDirName());
    }

    /**
     * Get the directory for a specific extension (~/.mcp-ade/extensions/{extensionId})
     */
    public Path getExtensionDir(String extensionId) {
        return getExtensionsDir().resolve(extensionId);
    }

    /**
     * Get the server home within an extension.
     * (~/.mcp-ade/extensions/{extensionId}/{type}/{serverId})
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
     * Get the config directory root (~/.mcp-ade/config)
     */
    public Path getConfigDir() {
        return getMcpAdeRoot().resolve(PathConfig.getConfigDirName());
    }

    /**
     * Get the settings file path (~/.mcp-ade/settings.json)
     */
    public Path getSettingsFile() {
        return getMcpAdeRoot().resolve(SETTINGS_JSON);
    }

}
