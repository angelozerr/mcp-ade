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
package org.eclipse.mcp.ade.bsp.server;

import org.eclipse.mcp.ade.extension.Extension;
import org.eclipse.mcp.ade.server.ServerConfigBase;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for a BSP (Build Server Protocol) server.
 * Can be loaded from JSON or built programmatically.
 */
public class BspServerConfig extends ServerConfigBase {

    /**
     * BSP initialization options sent during buildInitialize.
     */
    private Map<String, Object> initializationOptions = new HashMap<>();

    /**
     * Timeout in milliseconds to connect to the BSP server.
     * Default is 10 seconds since BSP servers like Gradle can be slow to start.
     */
    private int connectTimeout = 10000;

    public BspServerConfig(String serverId, Extension extension) {
        super(serverId, computeServerHome(serverId, extension), extension);
    }

    protected BspServerConfig(String serverId, Path serverHome, Extension extension) {
        super(serverId, serverHome, extension);
    }

    private static Path computeServerHome(String serverId, Extension extension) {
        return extension.getApplication().getPathManager()
                .getExtensionServerHome(extension.getId(), "bsp", serverId);
    }

    public Map<String, Object> getInitializationOptions() {
        return initializationOptions;
    }

    public void setInitializationOptions(Map<String, Object> initializationOptions) {
        this.initializationOptions = initializationOptions;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    /**
     * Check if this BSP server can handle the given workspace.
     * Uses the activation condition (fileExists, globPattern) if available.
     * If no activation condition is configured, defaults to true.
     *
     * @param workspaceUri the workspace URI
     * @param basePath     the workspace root path
     * @return true if this server can handle the workspace
     */
    public boolean canHandle(String workspaceUri, Path basePath) {
        // If there is an activation condition with fileExists, check it
        if (getActivateWhen() != null && getActivateWhen().getFileExists() != null) {
            return java.nio.file.Files.exists(basePath.resolve(getActivateWhen().getFileExists()));
        }
        // Default: can handle any workspace
        return true;
    }

    @Override
    public String toString() {
        return "BspServerConfig{" +
                "id='" + getServerId() + '\'' +
                ", name='" + getName() + '\'' +
                ", command='" + getCommand() + '\'' +
                '}';
    }

}
