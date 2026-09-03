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
package org.eclipse.mcp.ade.variable;

import org.eclipse.mcp.ade.configuration.PathConfig;
import org.eclipse.mcp.ade.installer.InstallableConfig;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves server-related variables: {@code extensionHome}, {@code serverHome},
 * {@code serverDist}, {@code userHome}, {@code mcpHome}, {@code workspaceFolder},
 * {@code workspaceRoot}, {@code workspaceStorageDir}.
 */
@Singleton
public class ServerVariableResolver implements VariableResolver {

    public static final String EXTENSION_HOME = "extensionHome";
    public static final String SERVER_HOME = "serverHome";
    public static final String SERVER_DIST = "serverDist";
    public static final String USER_HOME = "userHome";
    public static final String MCP_HOME = "mcpHome";
    public static final String WORKSPACE_FOLDER = "workspaceFolder";
    public static final String WORKSPACE_ROOT = "workspaceRoot";
    public static final String WORKSPACE_STORAGE_DIR = "workspaceStorageDir";

    @Override
    public String resolve(VariableExpression expression, VariableContext context) {
        if (expression.prefix() != null) {
            return null;
        }
        return switch (expression.name()) {
            case SERVER_HOME -> resolveServerHome(context);
            case SERVER_DIST -> resolveServerDist(context);
            case EXTENSION_HOME -> resolveExtensionHome(context);
            case USER_HOME -> System.getProperty("user.home");
            case MCP_HOME -> resolveMcpHome(context);
            case WORKSPACE_FOLDER, WORKSPACE_ROOT -> resolveWorkspaceFolder(context);
            case WORKSPACE_STORAGE_DIR -> resolveWorkspaceStorageDir(context);
            default -> null;
        };
    }

    private static String resolveServerHome(VariableContext context) {
        InstallableConfig config = context.getServerConfig();
        if (config == null) {
            return null;
        }
        Path serverHome = config.getServerHome();
        return serverHome != null ? serverHome.toString() : null;
    }

    private static String resolveServerDist(VariableContext context) {
        InstallableConfig config = context.getServerConfig();
        if (config == null) {
            return null;
        }
        Path serverDist = config.getServerDist();
        return serverDist != null ? serverDist.toString() : null;
    }

    private static String resolveExtensionHome(VariableContext context) {
        InstallableConfig config = context.getServerConfig();
        if (config == null) {
            return null;
        }
        Path extensionHome = config.getExtensionHome();
        return extensionHome != null ? extensionHome.toString() : null;
    }

    private static String resolveMcpHome(VariableContext context) {
        Path mcpAdeRoot = context.getMcpAdeRoot();
        if (mcpAdeRoot != null) {
            return mcpAdeRoot.toString();
        }
        // Fallback: derive from serverHome (<mcpHome>/<type>/<serverId>)
        InstallableConfig config = context.getServerConfig();
        if (config == null) {
            return null;
        }
        Path serverHome = config.getServerHome();
        if (serverHome == null) {
            return null;
        }
        Path parent = serverHome.getParent();
        return parent != null && parent.getParent() != null
                ? parent.getParent().toString()
                : null;
    }

    private static String resolveWorkspaceFolder(VariableContext context) {
        Path folder = context.getWorkspaceFolder();
        return folder != null ? folder.toString() : null;
    }

    private static String resolveWorkspaceStorageDir(VariableContext context) {
        String mcpHome = resolveMcpHome(context);
        if (mcpHome == null) {
            return null;
        }
        InstallableConfig config = context.getServerConfig();
        if (config == null) {
            return null;
        }
        Path workspaceFolder = context.getWorkspaceFolder();
        if (workspaceFolder == null) {
            return null;
        }
        String serverId = config.getServerId();
        String workspaceName = workspaceFolder.getFileName().toString();
        int hash = workspaceFolder.toUri().hashCode() & 0x7FFFFFFF;
        Path dir = Path.of(mcpHome)
                .resolve(PathConfig.getWorkspaceStorageDirName())
                .resolve(serverId)
                .resolve(workspaceName + "-" + hash);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return dir.toString();
        }
        return dir.toString();
    }
}
