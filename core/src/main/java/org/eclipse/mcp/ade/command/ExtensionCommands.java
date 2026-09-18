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
import org.eclipse.mcp.ade.extension.Extension;
import org.eclipse.mcp.ade.extension.ExtensionRegistry;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.eclipse.mcp.ade.bsp.server.BspServerConfig;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

/**
 * CLI commands for extension management.
 */
@ApplicationScoped
public class ExtensionCommands {

    @Inject
    Application application;

    @Command(domain = CommandDomain.EXTENSION, action = "list",
             description = "List all installed extensions with their servers and enabled state")
    public List<Map<String, Object>> listExtensions() {
        ExtensionRegistry registry = application.getExtensionRegistry();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Extension ext : registry.getExtensions()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", ext.getId());
            if (ext.getName() != null) {
                entry.put("name", ext.getName());
            }
            if (ext.getDescription() != null) {
                entry.put("description", ext.getDescription());
            }
            entry.put("source", ext.getSource().name());
            entry.put("enabled", registry.isExtensionEnabled(ext.getId()));
            entry.put("lspServers", toServerList(ext.getLspServerConfigs(), registry));
            entry.put("dapServers", toServerList(ext.getDapServerConfigs(), registry));
            entry.put("bspServers", toServerList(ext.getBspServerConfigs(), registry));
            result.add(entry);
        }
        return result;
    }

    @Command(domain = CommandDomain.EXTENSION, action = "add",
             description = "Add an extension from a source path (folder, ZIP, or JAR)")
    public Map<String, Object> addExtension(
            @CommandArg(name = "id", description = "Unique extension identifier") String extensionId,
            @CommandArg(name = "source", description = "Path to the source folder, ZIP, or JAR file") String source)
            throws IOException {
        ExtensionRegistry registry = application.getExtensionRegistry();
        Extension extension = registry.addExtension(extensionId, Paths.get(source), application);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("extensionId", extension.getId());
        result.put("lspServers", extension.getLspServerConfigs().stream()
                .map(LspServerConfig::getServerId).toList());
        result.put("dapServers", extension.getDapServerConfigs().stream()
                .map(DapServerConfig::getServerId).toList());
        result.put("bspServers", extension.getBspServerConfigs().stream()
                .map(BspServerConfig::getServerId).toList());
        return result;
    }

    @Command(domain = CommandDomain.EXTENSION, action = "remove",
             description = "Remove a user-installed extension and all its servers")
    public Map<String, Object> removeExtension(
            @CommandArg(name = "id", description = "Extension ID to remove") String extensionId) {
        application.getExtensionRegistry().removeExtension(extensionId);
        return Map.of("success", true, "message", "Extension '" + extensionId + "' removed");
    }

    @Command(domain = CommandDomain.EXTENSION, action = "enable",
             description = "Enable a previously disabled extension")
    public Map<String, Object> enableExtension(
            @CommandArg(name = "id", description = "Extension ID to enable") String extensionId) {
        application.enableExtension(extensionId);
        return Map.of("success", true, "message", "Extension '" + extensionId + "' enabled");
    }

    @Command(domain = CommandDomain.EXTENSION, action = "disable",
             description = "Disable an extension without removing it")
    public Map<String, Object> disableExtension(
            @CommandArg(name = "id", description = "Extension ID to disable") String extensionId) {
        application.disableExtension(extensionId);
        return Map.of("success", true, "message", "Extension '" + extensionId + "' disabled");
    }

    @Command(domain = CommandDomain.EXTENSION, action = "schemas",
             description = "Show JSON schemas for building an extension")
    public Map<String, Object> getSchemas() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mcpExtensionSchema", loadSchema("schemas/mcp-extension-schema.json"));
        result.put("lspServerSchema", loadSchema("schemas/lsp-server-schema.json"));
        result.put("dapServerSchema", loadSchema("schemas/dap-server-schema.json"));
        result.put("bspServerSchema", loadSchema("schemas/bsp-server-schema.json"));
        result.put("installerSchema", loadSchema("schemas/installer-schema.json"));
        return result;
    }

    private String loadSchema(String resourcePath) {
        try (var is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return "{\"error\": \"Schema not found: " + resourcePath + "\"}";
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{\"error\": \"Failed to load schema: " + e.getMessage() + "\"}";
        }
    }

    private static List<Map<String, Object>> toServerList(
            Collection<? extends ServerConfigBase> configs, ExtensionRegistry registry) {
        return configs.stream()
                .map(c -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("id", c.getServerId());
                    s.put("name", c.getName());
                    s.put("enabled", registry.isServerEnabled(c.getServerId()));
                    return s;
                })
                .toList();
    }
}
