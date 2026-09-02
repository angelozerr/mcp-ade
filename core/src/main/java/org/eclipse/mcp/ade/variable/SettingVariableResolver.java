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

import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.eclipse.mcp.ade.bsp.server.BspServerConfig;
import org.eclipse.mcp.ade.configuration.ApplicationConfiguration;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.eclipse.mcp.ade.installer.InstallableConfig;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import org.eclipse.mcp.ade.server.ServerSettingDescriptor;

/**
 * Resolves {@code ${setting:key}} variables by looking up server settings
 * from the application configuration, falling back to the default value
 * declared in the server's {@code server.json} settings descriptor.
 */
@Singleton
public class SettingVariableResolver implements VariableResolver {

    private static final String PREFIX = "setting";

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @Override
    public String resolve(VariableExpression expression, VariableContext context) {
        if (!PREFIX.equals(expression.prefix())) {
            return null;
        }
        String settingKey = expression.name();
        InstallableConfig config = context.getServerConfig();
        if (!(config instanceof ServerConfigBase serverConfig)) {
            return null;
        }

        String serverType = getServerType(serverConfig);
        String fullKey = serverType + "." + serverConfig.getServerId() + ".settings." + settingKey;

        String value = applicationConfiguration.getString(fullKey);
        if (value != null) {
            return value;
        }

        if (serverConfig.getSettings() != null) {
            for (ServerSettingDescriptor desc : serverConfig.getSettings()) {
                if (settingKey.equals(desc.key())) {
                    return desc.defaultValue();
                }
            }
        }

        return null;
    }

    private static String getServerType(ServerConfigBase config) {
        if (config instanceof LspServerConfig) return "lsp";
        if (config instanceof DapServerConfig) return "dap";
        if (config instanceof BspServerConfig) return "bsp";
        return "lsp";
    }
}
