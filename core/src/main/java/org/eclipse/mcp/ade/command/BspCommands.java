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
package org.eclipse.mcp.ade.command;

import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.extension.ExtensionRegistry;
import org.eclipse.mcp.ade.bsp.server.BspServerConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

/**
 * CLI commands for BSP server management.
 */
@ApplicationScoped
public class BspCommands {

    @Inject
    Application application;

    @Command(domain = CommandDomain.BSP, action = "list",
             description = "List all configured BSP servers")
    public List<Map<String, Object>> listServers() {
        ExtensionRegistry registry = application.getExtensionRegistry();
        List<Map<String, Object>> result = new ArrayList<>();
        for (BspServerConfig config : application.getBspServerConfigs()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", config.getServerId());
            entry.put("name", config.getName());
            entry.put("extensionId", config.getExtensionId());
            entry.put("enabled", registry.isServerEnabled(config.getServerId()));
            result.add(entry);
        }
        return result;
    }

    @Command(domain = CommandDomain.BSP, action = "add",
             description = "Add a BSP server from a source path")
    public Map<String, Object> addServer(
            @CommandArg(name = "source", description = "Path to the source folder, ZIP, or JAR file") String source,
            @CommandArg(name = "extension-id", description = "Optional extension ID", required = false) String extensionId)
            throws IOException {
        ExtensionRegistry registry = application.getExtensionRegistry();
        BspServerConfig config = registry.addBspServer(Paths.get(source), extensionId, application);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("serverId", config.getServerId());
        result.put("extensionId", config.getExtensionId());
        return result;
    }

    @Command(domain = CommandDomain.BSP, action = "remove",
             description = "Remove a BSP server")
    public Map<String, Object> removeServer(
            @CommandArg(name = "id", description = "Server ID to remove") String serverId) {
        application.getExtensionRegistry().removeBspServer(serverId);
        return Map.of("success", true, "message", "BSP server '" + serverId + "' removed");
    }

    @Command(domain = CommandDomain.BSP, action = "enable",
             description = "Enable a previously disabled BSP server")
    public Map<String, Object> enableServer(
            @CommandArg(name = "id", description = "Server ID to enable") String serverId) {
        application.enableServer(serverId);
        return Map.of("success", true, "message", "BSP server '" + serverId + "' enabled");
    }

    @Command(domain = CommandDomain.BSP, action = "disable",
             description = "Disable a BSP server")
    public Map<String, Object> disableServer(
            @CommandArg(name = "id", description = "Server ID to disable") String serverId) {
        application.disableBspServer(serverId);
        return Map.of("success", true, "message", "BSP server '" + serverId + "' disabled");
    }
}
