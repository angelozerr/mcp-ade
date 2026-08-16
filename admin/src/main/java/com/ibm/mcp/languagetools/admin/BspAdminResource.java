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

import com.ibm.mcp.languagetools.admin.dto.ErrorResponse;
import com.ibm.mcp.languagetools.admin.dto.StatusResponse;
import com.ibm.mcp.languagetools.bsp.server.BspServer;
import com.ibm.mcp.languagetools.bsp.server.BspServerConfig;
import com.ibm.mcp.languagetools.installer.TraceProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressStep;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.workspace.Workspace;
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

    // ========== BSP Configs ==========

    @GET
    @Path("/configs")
    public List<Map<String, Object>> listConfigs() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BspServerConfig config : application.getBspServerConfigs()) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", config.getServerId());
            dto.put("name", config.getName());
            dto.put("description", config.getDescription());
            dto.put("url", config.getUrl());
            dto.put("enabled", application.getExtensionRegistry().isServerEnabled(config.getServerId()));
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
        dto.put("description", config.getDescription());
        dto.put("url", config.getUrl());
        dto.put("enabled", application.getExtensionRegistry().isServerEnabled(config.getServerId()));
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
            TraceProgressMonitor progressMonitor = new TraceProgressMonitor(
                    traceCollector, 100.0, progressBroadcaster, taskId, serverId, title);
            progressMonitor.addStep(ProgressStep.INSTALLING, 0.50);
            progressMonitor.addStep(ProgressStep.STARTING, 0.10);
            progressMonitor.addStep(ProgressStep.INITIALIZING, 0.40);
            progressMonitor.initializeSteps();

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
            TraceProgressMonitor progressMonitor = new TraceProgressMonitor(
                    traceCollector, 100.0, progressBroadcaster, taskId, serverId, title);
            progressMonitor.addStep(ProgressStep.INSTALLING, 0.50);
            progressMonitor.addStep(ProgressStep.STARTING, 0.10);
            progressMonitor.addStep(ProgressStep.INITIALIZING, 0.40);
            progressMonitor.initializeSteps();

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
