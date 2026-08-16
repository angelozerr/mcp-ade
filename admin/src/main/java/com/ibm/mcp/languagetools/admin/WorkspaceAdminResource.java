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
package com.ibm.mcp.languagetools.admin;

import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.admin.dto.DapSessionDTO;
import com.ibm.mcp.languagetools.admin.dto.ErrorResponse;
import com.ibm.mcp.languagetools.admin.dto.IdeSettingDTO;
import com.ibm.mcp.languagetools.admin.dto.BspServerDTO;
import com.ibm.mcp.languagetools.admin.dto.LspServerDTO;
import com.ibm.mcp.languagetools.admin.dto.ServerDTOBuilder;
import com.ibm.mcp.languagetools.admin.dto.ServerSettingDTO;
import com.ibm.mcp.languagetools.admin.dto.StatusResponse;
import com.ibm.mcp.languagetools.admin.dto.WorkspaceDTO;
import com.ibm.mcp.languagetools.admin.ws.TraceLevelWsMessage;
import com.ibm.mcp.languagetools.configuration.ServerTrace;
import com.ibm.mcp.languagetools.dap.session.DapSessionManager;
import com.ibm.mcp.languagetools.installer.TraceProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressBroadcaster;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.workspace.Workspace;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Path("/api/admin")
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceAdminResource {

    private static final Logger LOG = Logger.getLogger(WorkspaceAdminResource.class);

    @Inject
    Application application;

    @Inject
    ServerDTOBuilder serverDTOBuilder;

    @Inject
    DapSessionManager dapSessionManager;

    @Inject
    ProgressBroadcaster progressBroadcaster;

    @Inject
    Event<TraceLevelWsMessage> traceLevelEvent;

    @GET
    @Path("/workspaces")
    public List<WorkspaceDTO> listWorkspaces() {
        return getCurrentWorkspaces();
    }

    /**
     * SSE stream REMOVED - use polling with GET /api/admin/workspaces instead.
     * SSE kept only for traces to avoid HTTP/1.1 connection pool exhaustion.
     * Event observers removed as they were only used for SSE broadcasting.
     */

    private List<WorkspaceDTO> getCurrentWorkspaces() {
        return application.getWorkspaces()
                .stream()
                .map(WorkspaceDTO::fromWorkspace)
                .toList();
    }

    @GET
    @Path("/workspaces/{uri}")
    public WorkspaceDTO getWorkspace(@PathParam("uri") String uriParam) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        return WorkspaceDTO.fromWorkspace(workspace);
    }

    /**
     * Get LSP servers for a workspace (loaded on demand when clicking "Servers" tab).
     */
    @GET
    @Path("/workspaces/{uri}/lsp-servers")
    public List<LspServerDTO> getLspServers(@PathParam("uri") String uriParam) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }

        var serverConfigs = application.getLspServerConfigs();
        return serverConfigs.stream()
                .map(config -> serverDTOBuilder.buildRuntime(config, workspace))
                .toList();
    }

    /**
     * Get BSP servers for a workspace with runtime status.
     */
    @GET
    @Path("/workspaces/{uri}/bsp-servers")
    public List<BspServerDTO> getBspServers(@PathParam("uri") String uriParam) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }

        var serverConfigs = application.getBspServerConfigs();
        return serverConfigs.stream()
                .map(config -> serverDTOBuilder.buildBspRuntime(config, workspace))
                .toList();
    }

    /**
     * Get resolved settings for a specific server in a workspace.
     * Settings are resolved with inheritance: workspace → application → default.
     */
    @GET
    @Path("/workspaces/{uri}/lsp-servers/{serverId}/settings")
    public List<ServerSettingDTO> getServerSettings(@PathParam("uri") String uriParam,
                                                     @PathParam("serverId") String serverId) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }
        var config = application.getLspServerConfigs().stream()
                .filter(c -> c.getServerId().equals(serverId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Server not found: " + serverId));
        var settings = serverDTOBuilder.buildSettings(config, workspace);
        List<ServerSettingDTO> result = new java.util.ArrayList<>();
        result.add(buildTraceLevelSetting("lsp", serverId, workspace));
        if (settings != null) {
            result.addAll(settings);
        }
        return result;
    }

    /**
     * Get IDE settings for a specific server in a workspace.
     * Reads from the workspace's IDE configuration (e.g. .vscode/settings.json)
     * and filters by the server's applicableSettings patterns.
     */
    @GET
    @Path("/workspaces/{uri}/lsp-servers/{serverId}/ide-settings")
    public List<IdeSettingDTO> getServerIdeSettings(@PathParam("uri") String uriParam,
                                                     @PathParam("serverId") String serverId) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }
        var config = application.getLspServerConfigs().stream()
                .filter(c -> c.getServerId().equals(serverId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Server not found: " + serverId));
        return serverDTOBuilder.buildIdeSettings(config, workspace);
    }

    /**
     * Get DAP sessions for a workspace (loaded on demand when clicking "Debuggers" tab).
     */
    @GET
    @Path("/workspaces/{uri}/dap-sessions")
    public List<DapSessionDTO> getDapSessions(@PathParam("uri") String uriParam) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        URI uri = URI.create(uriParam);
        return dapSessionManager.getSessionsForWorkspace(uri).stream()
                .map(DapSessionDTO::fromSession)
                .toList();
    }

    /**
     * Close a workspace: shutdown all its LSP servers and remove from memory.
     */
    @DELETE
    @Path("/workspaces/{uri}")
    public Response closeWorkspace(@PathParam("uri") String uriParam) {
        URI uri = URI.create(uriParam);

        application.closeWorkspace(uri).join();

        return Response.ok()
                .entity("{\"status\": \"closed\", \"uri\": \"" + uri + "\"}")
                .build();
    }

    /**
     * Refresh a workspace: sync file system changes with all running language servers.
     * Returns immediately with a taskId; progress is pushed via WebSocket.
     */
    @POST
    @Path("/workspaces/{uri}/refresh")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response refreshWorkspace(@PathParam("uri") String uriParam, Map<String, Object> body) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }

        String taskId = body != null && body.containsKey("taskId") ? body.get("taskId").toString() : null;
        if (taskId == null || taskId.isEmpty()) {
            throw new BadRequestException("taskId is required");
        }

        LOG.infof("Refreshing workspace %s (taskId=%s)", uri, taskId);
        workspace.refreshWorkspace(taskId);

        return Response.accepted()
                .entity(Map.of("taskId", taskId, "status", "running"))
                .build();
    }

    /**
     * Build workspace (auto full/incremental based on needsFullBuild flag).
     * Returns immediately; progress is pushed via WebSocket using the provided taskId.
     */
    @POST
    @Path("/workspaces/{uri}/build")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response buildWorkspace(@PathParam("uri") String uriParam, Map<String, Object> body) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }

        String taskId = body != null && body.containsKey("taskId") ? body.get("taskId").toString() : null;
        if (taskId == null || taskId.isEmpty()) {
            throw new BadRequestException("taskId is required");
        }

        LOG.infof("Building workspace %s (taskId=%s)", uri, taskId);
        workspace.buildWorkspace(taskId);

        return Response.accepted()
                .entity(Map.of("taskId", taskId, "status", "running"))
                .build();
    }

    /**
     * Toggle file watcher for a workspace (writes to workspace-level configuration).
     */
    @POST
    @Path("/workspaces/{uri}/file-watcher")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response toggleFileWatcher(@PathParam("uri") String uriParam, Map<String, Object> body) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }

        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        String scope = body.get("scope") != null ? body.get("scope").toString() : "workspace";

        if ("application".equals(scope)) {
            var config = application.getConfiguration();
            if (config instanceof com.ibm.mcp.languagetools.configuration.ApplicationConfiguration appConfig) {
                appConfig.setBoolean("fileWatchers.enabled", enabled);
            }
            for (Workspace ws : application.getWorkspaces()) {
                if (enabled) {
                    ws.startFileWatcherIfEnabled();
                } else {
                    ws.stopFileWatcher();
                }
            }
        } else {
            workspace.getWorkspaceConfiguration().setBoolean("fileWatchers.enabled", enabled);
            if (enabled) {
                workspace.startFileWatcherIfEnabled();
            } else {
                workspace.stopFileWatcher();
            }
        }

        var resolved = workspace.getWorkspaceConfiguration().resolveBoolean("fileWatchers.enabled", true);
        return Response.ok()
                .entity(Map.of(
                        "fileWatcherEnabled", resolved.value(),
                        "fileWatcherEnabledSource", resolved.source().name(),
                        "fileWatcherRunning", workspace.isFileWatcherRunning()))
                .build();
    }

    /**
     * Get resolved workspace settings with source info.
     */
    @GET
    @Path("/workspaces/{uri}/settings")
    public Response getWorkspaceSettings(@PathParam("uri") String uriParam) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }

        var wsConfig = workspace.getWorkspaceConfiguration();
        var fwEnabled = wsConfig.resolveBoolean("fileWatchers.enabled", true);

        return Response.ok()
                .entity(Map.of(
                        "fileWatchers.enabled", Map.of(
                                "value", fwEnabled.value(),
                                "source", fwEnabled.source().name()
                        ),
                        "overrides", wsConfig.getOverrides()
                ))
                .build();
    }

    /**
     * Set a workspace-level configuration override.
     */
    @PUT
    @Path("/workspaces/{uri}/settings/{key:.+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setWorkspaceSetting(@PathParam("uri") String uriParam,
                                        @PathParam("key") String key,
                                        Map<String, Object> body) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }

        Object value = body.get("value");
        workspace.getWorkspaceConfiguration().set(key, value);

        if ("fileWatchers.enabled".equals(key)) {
            if (Boolean.TRUE.equals(value)) {
                workspace.startFileWatcherIfEnabled();
            } else {
                workspace.stopFileWatcher();
            }
        }

        return Response.ok()
                .entity(Map.of("key", key, "value", value, "source", "WORKSPACE"))
                .build();
    }

    /**
     * Remove a workspace-level configuration override (revert to application).
     */
    @DELETE
    @Path("/workspaces/{uri}/settings/{key:.+}")
    public Response resetWorkspaceSetting(@PathParam("uri") String uriParam,
                                          @PathParam("key") String key) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }

        workspace.getWorkspaceConfiguration().remove(key);

        if ("fileWatchers.enabled".equals(key)) {
            boolean resolved = workspace.getWorkspaceConfiguration()
                    .resolveBoolean("fileWatchers.enabled", true).value();
            if (resolved) {
                workspace.startFileWatcherIfEnabled();
            } else {
                workspace.stopFileWatcher();
            }
        }

        var wsConfig = workspace.getWorkspaceConfiguration();
        var fwEnabled = wsConfig.resolveBoolean("fileWatchers.enabled", true);
        return Response.ok()
                .entity(Map.of("key", key, "source", fwEnabled.source().name(), "value", fwEnabled.value()))
                .build();
    }

    /**
     * Get settings for a DAP server in a workspace (trace level).
     */
    @GET
    @Path("/workspaces/{uri}/dap-servers/{serverId}/settings")
    public List<ServerSettingDTO> getDapServerSettings(@PathParam("uri") String uriParam,
                                                       @PathParam("serverId") String serverId) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        return List.of(buildTraceLevelSetting("dap", serverId, workspace));
    }

    private ServerSettingDTO buildTraceLevelSetting(String serverType, String serverId, Workspace workspace) {
        var resolved = workspace.getWorkspaceConfiguration().resolveString(
                serverType + "." + serverId + ".trace", ServerTrace.off.toString());
        return new ServerSettingDTO(
                "trace", "Trace Level", "Controls protocol message tracing",
                "enum", List.of("off", "messages", "verbose"), null,
                ServerTrace.off.toString(), resolved.value(), resolved.source().name()
        );
    }

    // ========== Workspace-scoped Trace Levels ==========

    @PUT
    @Path("/workspaces/{uri}/traces/lsp/{serverId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setWorkspaceLspTraceLevel(@PathParam("uri") String uriParam,
                                               @PathParam("serverId") String serverId,
                                               Map<String, Object> body) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        ServerTrace level = ServerTrace.fromValue(String.valueOf(body.get("traceLevel")));
        workspace.getWorkspaceConfiguration().setLspTraceLevel(serverId, level);
        traceLevelEvent.fire(new TraceLevelWsMessage("lsp", serverId, level.toString(), uriParam));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/workspaces/{uri}/traces/lsp/{serverId}")
    public Response resetWorkspaceLspTraceLevel(@PathParam("uri") String uriParam,
                                                 @PathParam("serverId") String serverId) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        workspace.getWorkspaceConfiguration().resetLspTraceLevel(serverId);
        return Response.noContent().build();
    }

    @PUT
    @Path("/workspaces/{uri}/traces/dap/{serverId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setWorkspaceDapTraceLevel(@PathParam("uri") String uriParam,
                                               @PathParam("serverId") String serverId,
                                               Map<String, Object> body) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        ServerTrace level = ServerTrace.fromValue(String.valueOf(body.get("traceLevel")));
        workspace.getWorkspaceConfiguration().setDapTraceLevel(serverId, level);
        traceLevelEvent.fire(new TraceLevelWsMessage("dap", serverId, level.toString(), uriParam));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/workspaces/{uri}/traces/dap/{serverId}")
    public Response resetWorkspaceDapTraceLevel(@PathParam("uri") String uriParam,
                                                 @PathParam("serverId") String serverId) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        workspace.getWorkspaceConfiguration().resetDapTraceLevel(serverId);
        return Response.noContent().build();
    }

    // ========== BSP Server Settings ==========

    @GET
    @Path("/workspaces/{uri}/bsp-servers/{serverId}/settings")
    public List<ServerSettingDTO> getBspServerSettings(@PathParam("uri") String uriParam,
                                                       @PathParam("serverId") String serverId) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        return List.of(buildTraceLevelSetting("bsp", serverId, workspace));
    }

    @PUT
    @Path("/workspaces/{uri}/traces/bsp/{serverId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setWorkspaceBspTraceLevel(@PathParam("uri") String uriParam,
                                               @PathParam("serverId") String serverId,
                                               Map<String, Object> body) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        ServerTrace level = ServerTrace.fromValue(String.valueOf(body.get("traceLevel")));
        workspace.getWorkspaceConfiguration().setBspTraceLevel(serverId, level);
        traceLevelEvent.fire(new TraceLevelWsMessage("bsp", serverId, level.toString(), uriParam));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/workspaces/{uri}/traces/bsp/{serverId}")
    public Response resetWorkspaceBspTraceLevel(@PathParam("uri") String uriParam,
                                                 @PathParam("serverId") String serverId) {
        Workspace workspace = getWorkspaceOrThrow(uriParam);
        workspace.getWorkspaceConfiguration().resetBspTraceLevel(serverId);
        return Response.noContent().build();
    }

    private Workspace getWorkspaceOrThrow(String uriParam) {
        URI uri = URI.create(uriParam);
        Workspace workspace = application.getWorkspace(uri);
        if (workspace == null) {
            throw new NotFoundException("Workspace not found: " + uri);
        }
        return workspace;
    }

}
