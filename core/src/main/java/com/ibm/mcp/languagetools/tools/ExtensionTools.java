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
package com.ibm.mcp.languagetools.tools;

import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.extension.ExtensionRegistry;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import com.ibm.mcp.languagetools.dap.server.DapServerConfig;
import com.ibm.mcp.languagetools.tools.ToolException;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * MCP tools for extension management: list, add, remove, enable, disable.
 */
@ApplicationScoped
public class ExtensionTools {

    private static final Logger LOG = Logger.getLogger(ExtensionTools.class);

    @Inject
    Application application;

    // ========== List ==========

    @Tool(name = "list_extensions",
          description = "List all installed extensions with their LSP/DAP servers, source (BUNDLED/USER), and enabled/disabled state.")
    public List<Map<String, Object>> listExtensions() {
        try {
            ExtensionRegistry registry = application.getExtensionRegistry();
            List<Map<String, Object>> result = new ArrayList<>();

            for (Extension ext : registry.getExtensions()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", ext.getId());
                entry.put("source", ext.getSource().name());
                entry.put("enabled", registry.isExtensionEnabled(ext.getId()));

                List<Map<String, Object>> lspServers = ext.getLspServerConfigs().stream()
                        .map(c -> {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("id", c.getServerId());
                            s.put("name", c.getName());
                            s.put("enabled", registry.isServerEnabled(c.getServerId()));
                            return s;
                        })
                        .toList();
                entry.put("lspServers", lspServers);

                List<Map<String, Object>> dapServers = ext.getDapServerConfigs().stream()
                        .map(c -> {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("id", c.getServerId());
                            s.put("name", c.getName());
                            s.put("enabled", registry.isServerEnabled(c.getServerId()));
                            return s;
                        })
                        .toList();
                entry.put("dapServers", dapServers);

                result.add(entry);
            }

            return result;
        } catch (Exception e) {
            LOG.error("Failed to list extensions", e);
            throw new ToolException("Failed to list extensions: " + e.getMessage(), e);
        }
    }

    // ========== Add ==========

    @Tool(name = "add_extension",
          description = "Add an extension from a source path (folder, ZIP, or JAR). " +
                        "The source must contain lsp/ and/or dap/ subdirectories with server configurations.")
    public Map<String, Object> addExtension(
            @ToolArg(description = "Unique extension identifier") String extensionId,
            @ToolArg(description = "Path to the source folder, ZIP, or JAR file") String source) {
        try {
            ExtensionRegistry registry = application.getExtensionRegistry();
            Extension extension = registry.addExtension(extensionId, Paths.get(source), application);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("extensionId", extension.getId());
            result.put("lspServers", extension.getLspServerConfigs().stream()
                    .map(LspServerConfig::getServerId).toList());
            result.put("dapServers", extension.getDapServerConfigs().stream()
                    .map(DapServerConfig::getServerId).toList());
            return result;
        } catch (Exception e) {
            LOG.error("Failed to add extension", e);
            throw new ToolException("Failed to add extension: " + e.getMessage(), e);
        }
    }

    @Tool(name = "add_lsp_server",
          description = "Add an LSP server from a source path (folder, ZIP, or JAR containing server.json). " +
                        "Optionally specify an extensionId to group with other servers.")
    public Map<String, Object> addLspServer(
            @ToolArg(description = "Path to the source folder, ZIP, or JAR file") String source,
            @ToolArg(description = "Optional extension ID (defaults to serverId from config)") String extensionId) {
        try {
            ExtensionRegistry registry = application.getExtensionRegistry();
            LspServerConfig config = registry.addLspServer(Paths.get(source), extensionId, application);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("serverId", config.getServerId());
            result.put("extensionId", config.getExtensionId());
            return result;
        } catch (Exception e) {
            LOG.error("Failed to add LSP server", e);
            throw new ToolException("Failed to add LSP server: " + e.getMessage(), e);
        }
    }

    @Tool(name = "add_dap_server",
          description = "Add a DAP server from a source path (folder, ZIP, or JAR containing server.json). " +
                        "Optionally specify an extensionId to group with other servers.")
    public Map<String, Object> addDapServer(
            @ToolArg(description = "Path to the source folder, ZIP, or JAR file") String source,
            @ToolArg(description = "Optional extension ID (defaults to serverId from config)") String extensionId) {
        try {
            ExtensionRegistry registry = application.getExtensionRegistry();
            DapServerConfig config = registry.addDapServer(Paths.get(source), extensionId, application);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("serverId", config.getServerId());
            result.put("extensionId", config.getExtensionId());
            return result;
        } catch (Exception e) {
            LOG.error("Failed to add DAP server", e);
            throw new ToolException("Failed to add DAP server: " + e.getMessage(), e);
        }
    }

    // ========== Remove ==========

    @Tool(name = "remove_extension",
          description = "Remove a user-installed extension and all its servers. Bundled extensions cannot be removed, use disable instead.")
    public Map<String, Object> removeExtension(
            @ToolArg(description = "Extension ID to remove") String extensionId) {
        try {
            application.getExtensionRegistry().removeExtension(extensionId);
            return Map.of("success", true, "message", "Extension '" + extensionId + "' removed");
        } catch (Exception e) {
            LOG.error("Failed to remove extension", e);
            throw new ToolException("Failed to remove extension: " + e.getMessage(), e);
        }
    }

    @Tool(name = "remove_lsp_server",
          description = "Remove an LSP server. If the extension becomes empty, it is also removed. Bundled servers cannot be removed.")
    public Map<String, Object> removeLspServer(
            @ToolArg(description = "Server ID to remove") String serverId) {
        try {
            application.getExtensionRegistry().removeLspServer(serverId);
            return Map.of("success", true, "message", "LSP server '" + serverId + "' removed");
        } catch (Exception e) {
            LOG.error("Failed to remove LSP server", e);
            throw new ToolException("Failed to remove LSP server: " + e.getMessage(), e);
        }
    }

    @Tool(name = "remove_dap_server",
          description = "Remove a DAP server. If the extension becomes empty, it is also removed. Bundled servers cannot be removed.")
    public Map<String, Object> removeDapServer(
            @ToolArg(description = "Server ID to remove") String serverId) {
        try {
            application.getExtensionRegistry().removeDapServer(serverId);
            return Map.of("success", true, "message", "DAP server '" + serverId + "' removed");
        } catch (Exception e) {
            LOG.error("Failed to remove DAP server", e);
            throw new ToolException("Failed to remove DAP server: " + e.getMessage(), e);
        }
    }

    // ========== Schemas ==========

    @Tool(name = "get_extension_schemas",
          description = "Returns the JSON Schemas for building an mcp-languagetools extension. " +
                        "An extension is a folder with the following structure: " +
                        "mcp-extension.json (optional root descriptor with extension id), " +
                        "lsp/<server-id>/server.json + installer.json (for each LSP language server), " +
                        "dap/<server-id>/server.json + installer.json (for each DAP debug adapter). " +
                        "Both lsp/ and dap/ are optional. " +
                        "Use the returned schemas to generate valid configuration files, " +
                        "then register the extension with the add_extension tool.")
    public Map<String, Object> getExtensionSchemas() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mcpExtensionSchema", loadSchema("schemas/mcp-extension-schema.json"));
        result.put("lspServerSchema", loadSchema("schemas/lsp-server-schema.json"));
        result.put("dapServerSchema", loadSchema("schemas/dap-server-schema.json"));
        result.put("installerSchema", loadSchema("schemas/installer-schema.json"));
        return result;
    }

    private String loadSchema(String resourcePath) {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                return "{\"error\": \"Schema not found: " + resourcePath + "\"}";
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("Failed to load schema: " + resourcePath, e);
            return "{\"error\": \"Failed to load schema: " + e.getMessage() + "\"}";
        }
    }

    // ========== Enable / Disable ==========

    @Tool(name = "enable_extension",
          description = "Enable a previously disabled extension. All its enabled servers become available again.")
    public Map<String, Object> enableExtension(
            @ToolArg(description = "Extension ID to enable") String extensionId) {
        try {
            application.getExtensionRegistry().enableExtension(extensionId);
            return Map.of("success", true, "message", "Extension '" + extensionId + "' enabled");
        } catch (Exception e) {
            LOG.error("Failed to enable extension", e);
            throw new ToolException("Failed to enable extension: " + e.getMessage(), e);
        }
    }

    @Tool(name = "disable_extension",
          description = "Disable an extension. All its servers become unavailable. The extension remains on disk.")
    public Map<String, Object> disableExtension(
            @ToolArg(description = "Extension ID to disable") String extensionId) {
        try {
            application.getExtensionRegistry().disableExtension(extensionId);
            return Map.of("success", true, "message", "Extension '" + extensionId + "' disabled");
        } catch (Exception e) {
            LOG.error("Failed to disable extension", e);
            throw new ToolException("Failed to disable extension: " + e.getMessage(), e);
        }
    }

    @Tool(name = "enable_lsp_server",
          description = "Enable a previously disabled LSP server.")
    public Map<String, Object> enableLspServer(
            @ToolArg(description = "Server ID to enable") String serverId) {
        try {
            application.enableLspServer(serverId);
            return Map.of("success", true, "message", "LSP server '" + serverId + "' enabled");
        } catch (Exception e) {
            LOG.error("Failed to enable LSP server", e);
            throw new ToolException("Failed to enable LSP server: " + e.getMessage(), e);
        }
    }

    @Tool(name = "disable_lsp_server",
          description = "Disable an LSP server. It becomes unavailable for new requests.")
    public Map<String, Object> disableLspServer(
            @ToolArg(description = "Server ID to disable") String serverId) {
        try {
            application.disableLspServer(serverId);
            return Map.of("success", true, "message", "LSP server '" + serverId + "' disabled");
        } catch (Exception e) {
            LOG.error("Failed to disable LSP server", e);
            throw new ToolException("Failed to disable LSP server: " + e.getMessage(), e);
        }
    }

    @Tool(name = "enable_dap_server",
          description = "Enable a previously disabled DAP server.")
    public Map<String, Object> enableDapServer(
            @ToolArg(description = "Server ID to enable") String serverId) {
        try {
            application.enableDapServer(serverId);
            return Map.of("success", true, "message", "DAP server '" + serverId + "' enabled");
        } catch (Exception e) {
            LOG.error("Failed to enable DAP server", e);
            throw new ToolException("Failed to enable DAP server: " + e.getMessage(), e);
        }
    }

    @Tool(name = "disable_dap_server",
          description = "Disable a DAP server. It becomes unavailable for new debug sessions.")
    public Map<String, Object> disableDapServer(
            @ToolArg(description = "Server ID to disable") String serverId) {
        try {
            application.disableDapServer(serverId);
            return Map.of("success", true, "message", "DAP server '" + serverId + "' disabled");
        } catch (Exception e) {
            LOG.error("Failed to disable DAP server", e);
            throw new ToolException("Failed to disable DAP server: " + e.getMessage(), e);
        }
    }
}
