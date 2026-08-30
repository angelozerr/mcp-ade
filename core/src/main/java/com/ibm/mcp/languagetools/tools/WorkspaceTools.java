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
import com.ibm.mcp.languagetools.utils.UriUtils;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.server.LspServerResolver;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.tools.ToolException;
import com.ibm.mcp.languagetools.workspace.Workspace;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.jboss.logging.Logger;

import java.nio.file.Path;
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

    @Inject
    LspServerResolver serverResolver;

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
            throw new ToolException("Failed to list workspaces: " + e.getMessage(), e);
        }
    }

    @Tool(
            name = "refresh_workspace",
            description = "Refresh all running language servers in a workspace to synchronize with file system changes. " +
                        "Call this after creating, modifying or deleting files (e.g., new Java classes) " +
                        "so that language servers detect the changes and update their internal model. " +
                        "This is essential before debugging or building when files were created outside of LSP.")
    public String refreshWorkspace(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd) {
        try {
            Workspace workspace = application.getWorkspaceForPath(cwd);
            if (workspace == null) {
                throw new ToolException("No workspace found for: " + cwd);
            }

            workspace.refreshActivationCache();

            String result = workspace.refreshWorkspace()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
            return "Workspace refreshed: " + workspace.getRootUri() + "\n" + result;
        } catch (Exception e) {
            LOG.error("Failed to refresh workspace", e);
            throw new ToolException("Failed to refresh workspace: " + e.getMessage(), e);
        }
    }

    @Tool(
            name = "build_workspace",
            description = "Build all running language servers in a workspace. " +
                        "Automatically chooses between full and incremental build: " +
                        "incremental if the file watcher has been tracking changes continuously, " +
                        "full if changes may have been missed (file watcher was disabled, server just started, etc.). " +
                        "Call this before debugging to ensure all sources are compiled.")
    public String buildWorkspace(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd) {
        try {
            Workspace workspace = application.getWorkspaceForPath(cwd);
            if (workspace == null) {
                throw new ToolException("No workspace found for: " + cwd);
            }

            boolean wasFull = workspace.isNeedsFullBuild();
            String result = workspace.buildWorkspace()
                    .get(60, java.util.concurrent.TimeUnit.SECONDS);
            return "Workspace built (" + (wasFull ? "full" : "incremental") + "): "
                    + workspace.getRootUri() + "\n" + result;
        } catch (Exception e) {
            LOG.error("Failed to build workspace", e);
            throw new ToolException("Failed to build workspace: " + e.getMessage(), e);
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

                if (config.getRuntime() != null) {
                    server.put("runtime", config.getRuntime());
                    if (config.getRuntimeConfig() != null) {
                        config.getRuntimeConfig().addRuntimeInfo(server);
                    }
                }

                String extensionId = config.getExtensionId();
                if (extensionId != null) {
                    server.put("extensionId", extensionId);
                    if (config.getExtensionName() != null) {
                        server.put("extensionName", config.getExtensionName());
                    }
                }
                server.put("enabled", serverResolver.isEnabled(config));

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

    @Tool(
            name = "notify_file_changes",
            description = "Notify language servers about file changes on the file system. " +
                        "Use this when you have created, modified, or deleted files and want to " +
                        "inform language servers immediately without waiting for the file watcher. " +
                        "Each change should specify the file path and change type (created/changed/deleted).")
    public String notifyFileChanges(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "List of file changes. Each entry: 'path:type' where type is 'created', 'changed', or 'deleted'. Example: 'src/Main.java:created'") List<String> changes) {
        try {
            Workspace workspace = application.getWorkspaceForPath(cwd);
            if (workspace == null) {
                throw new ToolException("No workspace found for: " + cwd);
            }

            List<FileEvent> events = new ArrayList<>();
            for (String change : changes) {
                int colonIdx = change.lastIndexOf(':');
                if (colonIdx <= 0) {
                    continue;
                }
                String filePath = change.substring(0, colonIdx).trim();
                String type = change.substring(colonIdx + 1).trim().toLowerCase();

                FileChangeType changeType = switch (type) {
                    case "created" -> FileChangeType.Created;
                    case "changed" -> FileChangeType.Changed;
                    case "deleted" -> FileChangeType.Deleted;
                    default -> null;
                };
                if (changeType == null) {
                    continue;
                }

                String uri = UriUtils.toFileUriString(workspace.getRootPath().resolve(filePath).toUri());
                events.add(new FileEvent(uri, changeType));
            }

            if (events.isEmpty()) {
                throw new ToolException("No valid file changes provided");
            }

            workspace.notifyFileChanges(events);
            return String.format("Notified %d file change(s) to language servers", events.size());
        } catch (Exception e) {
            LOG.error("Failed to notify file changes", e);
            throw new ToolException("Failed to notify file changes: " + e.getMessage(), e);
        }
    }
}
