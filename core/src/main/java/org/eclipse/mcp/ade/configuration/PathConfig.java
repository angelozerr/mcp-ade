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
package org.eclipse.mcp.ade.configuration;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Configuration for MCP Language Tools paths.
 * All paths are configurable via application.properties.
 */
@ApplicationScoped
public class PathConfig {

    // Directory structure (fixed)
    private static final String DIR_MCP_LANG_TOOLS = ".mcp-ade";
    private static final String DIR_LSP = "lsp";
    private static final String DIR_DAP = "dap";
    private static final String DIR_BSP = "bsp";
    private static final String DIR_CONFIG = "config";
    private static final String DIR_WORKSPACES = "workspaces";

    @ConfigProperty(name = "mcp.ade.root")
    Optional<String> rootDir;

    /**
     * Get the root directory (user home by default).
     */
    public Path getRootDir() {
        String root = rootDir.orElse(System.getProperty("user.home"));
        return Paths.get(root);
    }

    /**
     * Get the main MCP Language Tools directory.
     * Defaults to ~/.mcp-ade
     */
    public Path getMcpAdeDir() {
        return getRootDir().resolve(DIR_MCP_LANG_TOOLS);
    }

    /**
     * Get the LSP servers directory name (lsp).
     */
    public static String getLspDirName() {
        return DIR_LSP;
    }

    /**
     * Get the DAP servers directory name (dap).
     */
    public static String getDapDirName() {
        return DIR_DAP;
    }

    /**
     * Get the BSP servers directory name (bsp).
     */
    public static String getBspDirName() {
        return DIR_BSP;
    }

    /**
     * Get the config directory name (config).
     */
    public String getConfigDirName() {
        return DIR_CONFIG;
    }

    /**
     * Get the workspaces directory name (workspaces).
     */
    public String getWorkspacesDirName() {
        return DIR_WORKSPACES;
    }

}
