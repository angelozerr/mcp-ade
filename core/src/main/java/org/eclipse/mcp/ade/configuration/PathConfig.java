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

    private static final String DIR_MCP_ADE = ".mcp-ade";
    private static final String DIR_LSP = "lsp";
    private static final String DIR_DAP = "dap";
    private static final String DIR_BSP = "bsp";
    private static final String DIR_CONFIG = "config";
    private static final String DIR_WORKSPACE_STORAGE = "workspace-storage";
    private static final String DIR_RUNTIMES = "runtimes";
    private static final String DIR_EXTENSIONS = "extensions";

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
     * Get the main MCP ADE directory.
     * Defaults to ~/.mcp-ade
     */
    public Path getMcpAdeDir() {
        return getRootDir().resolve(DIR_MCP_ADE);
    }

    public static String getMcpAdeDirName() {
        return DIR_MCP_ADE;
    }

    public static String getLspDirName() {
        return DIR_LSP;
    }

    public static String getDapDirName() {
        return DIR_DAP;
    }

    public static String getBspDirName() {
        return DIR_BSP;
    }

    public static String getConfigDirName() {
        return DIR_CONFIG;
    }

    public static String getWorkspaceStorageDirName() {
        return DIR_WORKSPACE_STORAGE;
    }

    public static String getRuntimesDirName() {
        return DIR_RUNTIMES;
    }

    public static String getExtensionsDirName() {
        return DIR_EXTENSIONS;
    }

}
