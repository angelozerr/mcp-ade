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
import com.ibm.mcp.languagetools.extension.ExtensionRegistry;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.workspace.Workspace;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP tools for workspace management.
 */
@ApplicationScoped
public class WorkspaceTools {

    private static final Logger LOG = Logger.getLogger(WorkspaceTools.class);

    @Inject
    Application application;

    @Tool(
            name="list_workspaces",
            description = "Get information about all active workspaces, including root URIs and language server count. " +
                        "Workspaces are initialized automatically when using diagnostics tools.")
    public String listWorkspaces() {
        try {
            Collection<Workspace> workspaces = application.getWorkspaces();
            if (workspaces.isEmpty()) {
                return "No workspaces currently active";
            }

            return workspaces.stream()
                    .map(workspace -> String.format("- %s (%d language servers)",
                            workspace.getRootUri(),
                            workspace.getLspServers().size()))
                    .collect(Collectors.joining("\n", "Active workspaces:\n", ""));

        } catch (Exception e) {
            LOG.error("Failed to list workspaces", e);
            return "Failed to list workspaces: " + e.getMessage();
        }
    }

    @Tool(
            name = "refresh_workspace",
            description = "Re-evaluate activation conditions for all language servers in a workspace. " +
                        "Use this after project configuration changes (e.g., adding angular.json) " +
                        "to activate or deactivate language servers accordingly.")
    public String refreshWorkspace(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd) {
        try {
            Workspace workspace = application.getWorkspaceForPath(cwd);
            if (workspace == null) {
                return "No workspace found for: " + cwd;
            }
            workspace.refreshActivationCache();
            return "Workspace refreshed: " + workspace.getRootUri();
        } catch (Exception e) {
            LOG.error("Failed to refresh workspace", e);
            return "Failed to refresh workspace: " + e.getMessage();
        }
    }

    @Tool(
            name = "list_language_servers",
            description = "Get information about configured language servers (ID, name, description, supported languages). " +
                        "Without cwd: returns available server configurations. " +
                        "With cwd: returns server configurations enriched with runtime state for the given workspace " +
                        "(status, ready, statusMessage) to help diagnose server issues.")
    public List<Map<String, Object>> listLanguageServers(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd) {
        {
            Workspace workspace = (cwd != null && !cwd.isEmpty())
                    ? application.getWorkspaceForPath(cwd)
                    : null;
            var configs = application.getLspServerConfigs();
            List<Map<String, Object>> result = new ArrayList<>();
            for (var config : configs) {
                Map<String, Object> server = new LinkedHashMap<>();
                server.put("id", config.getServerId());
                server.put("name", config.getName());
                server.put("description", config.getDescription() != null ? config.getDescription() : "");
                if (config.getUrl() != null) {
                    server.put("url", config.getUrl());
                }

                List<String> languages = new ArrayList<>();
                if (config.getDocumentSelector() != null) {
                    languages.addAll(config.getDocumentSelector().getLanguages());
                }
                server.put("languages", languages);

                ExtensionRegistry extRegistry = application.getExtensionRegistry();
                String extensionId = config.getExtensionId();
                if (extensionId != null) {
                    server.put("extensionId", extensionId);
                }
                boolean enabled = extRegistry.isExtensionEnabled(extensionId != null ? extensionId : config.getServerId())
                        && extRegistry.isServerEnabled(config.getServerId());
                server.put("enabled", enabled);

                if (workspace != null) {
                    LspServer lspServer = workspace.getLspServer(config.getServerId());
                    if (lspServer != null) {
                        server.put("status", lspServer.getStatus().name());
                        server.put("ready", lspServer.isReady());
                        String statusMessage = lspServer.getStatusMessage();
                        if (statusMessage != null) {
                            server.put("statusMessage", statusMessage);
                        }
                        String errorMessage = lspServer.getErrorMessage();
                        if (errorMessage != null) {
                            server.put("error", errorMessage);
                        }
                    } else {
                        server.put("status", ServerStatus.NOT_STARTED.name());
                        server.put("ready", false);
                    }
                    config.addInstallationStatus(server);
                }

                result.add(server);
            }

            return result;
        }
    }
}
