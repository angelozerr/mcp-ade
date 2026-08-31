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

import org.eclipse.mcp.ade.admin.dto.ContributionDTOBuilder;
import org.eclipse.mcp.ade.admin.dto.ErrorResponse;
import org.eclipse.mcp.ade.admin.dto.LspConfigDTO;
import org.eclipse.mcp.ade.admin.dto.ServerDTOBuilder;
import org.eclipse.mcp.ade.admin.dto.StatusResponse;
import org.eclipse.mcp.ade.installer.TraceProgressMonitor;
import org.eclipse.mcp.ade.trace.TraceCollector;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import org.eclipse.mcp.ade.workspace.Workspace;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for all LSP-related admin operations.
 * Consolidates LSP config listing, details, installer, control, and trace configuration.
 */
@Path("/api/admin/lsp")
@Produces(MediaType.APPLICATION_JSON)
public class LspAdminResource extends AbstractServerAdminResource {

    private static final Logger LOG = Logger.getLogger(LspAdminResource.class);

    @Inject
    ServerDTOBuilder serverDTOBuilder;

    @Inject
    ContributionDTOBuilder contributionBuilder;

    @Override
    protected ServerConfigBase getServerConfig(String serverId) {
        return application.getLspServerConfig(serverId);
    }

    @Override
    protected String getServerType() {
        return "LSP";
    }

    @Override
    protected TraceCollector getTraceCollector() {
        return application.getLspTraceCollector();
    }

    // ========== LSP Configs ==========

    /**
     * List all configured LSP servers (static config, independent of workspaces).
     */
    @GET
    @Path("/configs")
    public List<LspConfigDTO> listConfigs() {
        var configs = application.getLspServerConfigs();
        checkUncheckedServers(configs);
        return configs.stream()
                .map(serverDTOBuilder::buildConfigSummary)
                .toList();
    }

    /**
     * Get details of a specific LSP config.
     */
    @GET
    @Path("/configs/{serverId}")
    public LspConfigDTO getConfig(@PathParam("serverId") String serverId) {
        LspServerConfig config = application.getLspServerConfig(serverId);

        if (config == null) {
            throw new NotFoundException("LSP server not found: " + serverId);
        }

        return serverDTOBuilder.buildConfig(config);
    }

    /**
     * Get contributions for a specific server (both what it contributes to and what contributes to it).
     */
    @GET
    @Path("/configs/{serverId}/contributions")
    public Map<String, Object> getContributions(@PathParam("serverId") String serverId) {
        LspServerConfig config = application.getLspServerConfig(serverId);
        if (config == null) {
            throw new NotFoundException("LSP server not found: " + serverId);
        }
        List<ServerConfigBase> allConfigs = new ArrayList<>();
        allConfigs.addAll(application.getLspServerConfigs());
        allConfigs.addAll(application.getDapServerConfigs());
        return contributionBuilder.buildContributionsView(serverId, config, allConfigs);
    }

    // ========== LSP Server Control ==========

    /**
     * Stop an LSP server in a workspace.
     */
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

            var server = workspace.getLspServer(serverId);
            if (server == null) {
                return Response.status(404).entity(new ErrorResponse("Server not found")).build();
            }

            server.shutdown().join();
            return Response.ok().entity(new StatusResponse("stopped")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    /**
     * Restart an LSP server in a workspace.
     */
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

            // Restart from Admin UI - create ProgressMonitor with steps for user feedback
            LspServer server = workspace.getLspServer(serverId);
            String taskId = "restart-" + serverId;
            String title = "Restart " + serverId;
            TraceProgressMonitor progressMonitor = createServerStartMonitor(
                    server.getTraceCollector(), taskId, serverId, title);

            workspace.restartLspServer(serverId, progressMonitor)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            LOG.errorf(ex, "Failed to restart server '%s'", serverId);
                            progressMonitor.setFailed(ex.getMessage());
                        } else {
                            progressMonitor.setComplete();
                        }
                    });

            return Response.ok().entity(new StatusResponse("restarted")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    /**
     * Start a managed LSP server in a workspace.
     */
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

            var existingServer = workspace.getLspServer(serverId);
            if (existingServer != null) {
                // Start from Admin UI - create ProgressMonitor with steps for user feedback
                String taskId = "start-" + serverId;
                String title = "Start " + serverId;
                TraceProgressMonitor progressMonitor = createServerStartMonitor(
                        existingServer.getTraceCollector(), taskId, serverId, title);

                workspace.startManagedLspServer(serverId, progressMonitor)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                LOG.errorf(ex, "Failed to start server '%s'", serverId);
                                progressMonitor.setFailed(ex.getMessage());
                            } else {
                                progressMonitor.setComplete();
                            }
                        });
            } else {
                application.ensureServerStarted(serverId, workspaceUri);
            }

            return Response.ok().entity(new StatusResponse("starting")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    /**
     * Cancel an LSP progress task by sending window/workDoneProgress/cancel to the server.
     */
    @POST
    @Path("/progress/cancel-lsp-progress/{serverId}/{token}")
    public Response cancelLspProgress(@PathParam("serverId") String serverId,
                                       @PathParam("token") String token) {
        for (Workspace workspace : application.getWorkspaces()) {
            LspServer server = workspace.getLspServer(serverId);
            if (server != null && server.getLanguageServer() != null) {
                server.cancelLspProgress(token);
                return Response.ok().entity(new StatusResponse("cancelled")).build();
            }
        }
        return Response.status(404).entity(new ErrorResponse("Server not found: " + serverId)).build();
    }

    /**
     * Disconnect from IDE instance.
     */
    @POST
    @Path("/servers/{workspaceUri}/{serverId}/disconnect")
    public Response disconnectFromIde(@PathParam("workspaceUri") String workspaceUriParam,
                                       @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = application.getWorkspace(workspaceUri);

            if (workspace == null) {
                return Response.status(404).entity(new ErrorResponse("Workspace not found")).build();
            }

            var server = workspace.getLspServer(serverId);
            if (server == null) {
                return Response.status(404).entity(new ErrorResponse("Server not found")).build();
            }

            server.shutdown().join();
            return Response.ok().entity(new StatusResponse("disconnected")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

    /**
     * Connect to IDE instance.
     */
    @POST
    @Path("/servers/{workspaceUri}/{serverId}/connect-ide")
    public Response connectToIde(@PathParam("workspaceUri") String workspaceUriParam,
                                  @PathParam("serverId") String serverId) {
        try {
            URI workspaceUri = URI.create(workspaceUriParam);
            Workspace workspace = application.getWorkspace(workspaceUri);

            if (workspace == null) {
                return Response.status(404).entity(new ErrorResponse("Workspace not found")).build();
            }

            var externalInstance = workspace.getExternalInstance(serverId);
            if (externalInstance == null) {
                return Response.status(404).entity(new ErrorResponse("No IDE instance available for this server")).build();
            }

            // Connect to IDE from Admin UI - create ProgressMonitor for user feedback
            LspServer server = workspace.getLspServer(serverId);
            String taskId = "connect-" + serverId;
            String title = "Connect " + serverId;
            TraceProgressMonitor progressMonitor = new TraceProgressMonitor(
                    server.getTraceCollector(), 100.0, progressBroadcaster, taskId, serverId, title);

            workspace.restartLspServer(serverId, progressMonitor)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            LOG.errorf(error, "Failed to connect server '%s' to IDE", serverId);
                            progressMonitor.setFailed(error.getMessage());
                        } else {
                            progressMonitor.setComplete();
                        }
                    });

            return Response.accepted().entity(new StatusResponse("connecting")).build();
        } catch (Exception e) {
            return Response.status(500).entity(new ErrorResponse(e.getMessage())).build();
        }
    }

}
