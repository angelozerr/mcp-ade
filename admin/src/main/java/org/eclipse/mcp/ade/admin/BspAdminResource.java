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
package org.eclipse.mcp.ade.admin;

import org.eclipse.mcp.ade.admin.dto.ErrorResponse;
import org.eclipse.mcp.ade.admin.dto.StatusResponse;
import org.eclipse.mcp.ade.bsp.server.BspServer;
import org.eclipse.mcp.ade.bsp.server.BspServerConfig;
import org.eclipse.mcp.ade.installer.TraceProgressMonitor;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import org.eclipse.mcp.ade.trace.TraceCollector;
import org.eclipse.mcp.ade.workspace.Workspace;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Path("/api/admin/bsp")
@Produces(MediaType.APPLICATION_JSON)
public class BspAdminResource extends AbstractServerAdminResource {

    private static final Logger LOG = Logger.getLogger(BspAdminResource.class);

    @Override
    protected ServerConfigBase getServerConfig(String serverId) {
        return application.getBspServerConfig(serverId);
    }

    @Override
    protected String getServerType() {
        return "BSP";
    }

    @Override
    protected TraceCollector getTraceCollector() {
        return application.getBspTraceCollector();
    }

    // ========== BSP Configs ==========

    @GET
    @Path("/configs")
    public List<Map<String, Object>> listConfigs() {
        var bspConfigs = application.getBspServerConfigs();
        checkUncheckedServers(bspConfigs);
        List<Map<String, Object>> result = new ArrayList<>();
        for (BspServerConfig config : bspConfigs) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", config.getServerId());
            dto.put("name", config.getName());
            if (application.getExtensionRegistry().isServerEnabled(config.getServerId())) {
                dto.put("enabled", true);
            }
            if (config.getInstaller() != null) {
                dto.put("hasInstaller", true);
                dto.put("installationStatus", config.getStatus().name());
            }
            result.add(dto);
        }
        return result;
    }

    @GET
    @Path("/configs/{serverId}")
    public Map<String, Object> getConfig(@PathParam("serverId") String serverId) {
        BspServerConfig config = application.getBspServerConfig(serverId);
        if (config == null) {
            throw new NotFoundException("BSP server not found: " + serverId);
        }
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", config.getServerId());
        dto.put("name", config.getName());
        if (config.getDescription() != null) {
            dto.put("description", config.getDescription());
        }
        if (config.getUrl() != null) {
            dto.put("url", config.getUrl());
        }
        if (application.getExtensionRegistry().isServerEnabled(config.getServerId())) {
            dto.put("enabled", true);
        }
        if (config.getInstaller() != null) {
            dto.put("hasInstaller", true);
            dto.put("installationStatus", config.getStatus().name());
            dto.put("installDir", config.getServerHome().toString());
        }
        if (config.getExtensionId() != null) {
            dto.put("extensionId", config.getExtensionId());
        }
        if (config.getExtensionName() != null) {
            dto.put("extensionName", config.getExtensionName());
        }
        return dto;
    }

    // ========== BSP Server Control ==========

    @POST
    @Path("/servers/{workspaceUri}/{serverId}/stop")
    public Response stopServer(@PathParam("workspaceUri") String workspaceUriParam,
                                @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = application.getWorkspace(workspaceUri);
            if (workspace == null) {
                return Response.status(404).entity(new ErrorResponse("Workspace not found")).build();
            }
            BspServer server = workspace.getBspServer(serverId);
            if (server == null) {
                return Response.status(404).entity(new ErrorResponse("Server not found")).build();
            }
            server.shutdown().join();
            return Response.ok().entity(new StatusResponse("stopped")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/servers/{workspaceUri}/{serverId}/restart")
    public Response restartServer(@PathParam("workspaceUri") String workspaceUriParam,
                                   @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = application.getWorkspace(workspaceUri);
            if (workspace == null) {
                return Response.status(404).entity(new ErrorResponse("Workspace not found")).build();
            }
            BspServer oldServer = workspace.getBspServer(serverId);
            TraceCollector traceCollector = oldServer != null ? oldServer.getTraceCollector() : application.getBspTraceCollector();
            String taskId = "restart-bsp-" + serverId;
            String title = "Restart BSP " + serverId;
            TraceProgressMonitor progressMonitor = createServerStartMonitor(
                    traceCollector, taskId, serverId, title);

            CompletableFuture<Void> shutdownFuture = (oldServer != null) ? oldServer.shutdown() : CompletableFuture.completedFuture(null);
            shutdownFuture.thenCompose(v -> workspace.ensureBspServerStarted(serverId, progressMonitor))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            LOG.errorf(ex, "Failed to restart BSP server '%s'", serverId);
                            progressMonitor.setFailed(ex.getMessage());
                        } else {
                            progressMonitor.setComplete();
                        }
                    });

            return Response.ok().entity(new StatusResponse("restarting")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    @POST
    @Path("/servers/{workspaceUri}/{serverId}/start-managed")
    public Response startManagedServer(@PathParam("workspaceUri") String workspaceUriParam,
                                        @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = application.getWorkspace(workspaceUri);
            if (workspace == null) {
                return Response.status(404).entity(new ErrorResponse("Workspace not found")).build();
            }
            TraceCollector traceCollector = application.getBspTraceCollector();
            String taskId = "start-bsp-" + serverId;
            String title = "Start BSP " + serverId;
            TraceProgressMonitor progressMonitor = createServerStartMonitor(
                    traceCollector, taskId, serverId, title);

            workspace.ensureBspServerStarted(serverId, progressMonitor)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            LOG.errorf(ex, "Failed to start BSP server '%s'", serverId);
                            progressMonitor.setFailed(ex.getMessage());
                        } else {
                            progressMonitor.setComplete();
                        }
                    });

            return Response.ok().entity(new StatusResponse("starting")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }
}
