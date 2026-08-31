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

import org.eclipse.mcp.ade.installer.InstallableConfig;
import jakarta.inject.Singleton;

import java.nio.file.Path;

/**
 * Resolves server-related variables: {@code serverHome}, {@code userHome},
 * {@code mcpHome}, {@code workspaceFolder}, {@code workspaceRoot}.
 */
@Singleton
public class ServerVariableResolver implements VariableResolver {

    @Override
    public String resolve(VariableExpression expression, VariableContext context) {
        if (expression.prefix() != null) {
            return null;
        }
        return switch (expression.name()) {
            case "serverHome" -> resolveServerHome(context);
            case "userHome" -> System.getProperty("user.home");
            case "mcpHome" -> resolveMcpHome(context);
            case "workspaceFolder", "workspaceRoot" -> resolveWorkspaceFolder(context);
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

    private static String resolveMcpHome(VariableContext context) {
        InstallableConfig config = context.getServerConfig();
        if (config == null) {
            return null;
        }
        Path serverHome = config.getServerHome();
        if (serverHome == null) {
            return null;
        }
        // MCP_HOME is two levels up from serverHome: <mcpHome>/<type>/<serverId>
        Path parent = serverHome.getParent();
        return parent != null && parent.getParent() != null
                ? parent.getParent().toString()
                : null;
    }

    private static String resolveWorkspaceFolder(VariableContext context) {
        Path folder = context.getWorkspaceFolder();
        return folder != null ? folder.toString() : null;
    }
}
