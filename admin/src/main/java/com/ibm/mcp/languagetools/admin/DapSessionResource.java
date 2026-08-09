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
import com.ibm.mcp.languagetools.admin.dto.CreateDapSessionRequest;
import com.ibm.mcp.languagetools.admin.dto.ErrorResponse;
import com.ibm.mcp.languagetools.dap.server.DapServerConfig;
import com.ibm.mcp.languagetools.dap.session.DapSession;
import com.ibm.mcp.languagetools.operation.OperationActor;
import com.ibm.mcp.languagetools.dap.session.DapSessionManager;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Path("/api/admin/dap/sessions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DapSessionResource {

    private static final Logger LOG = Logger.getLogger(DapSessionResource.class);

    @Inject
    DapSessionManager sessionManager;

    @Inject
    Application application;

    /**
     * List all active DAP sessions.
     */
    @GET
    public Response listSessions() {
        LOG.debugf("Listing all DAP sessions");

        try {
            List<Map<String, Object>> sessions = sessionManager.listSessions();
            return Response.ok(sessions).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to list DAP sessions");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.fromException(e))
                    .build();
        }
    }

    /**
     * Create a new DAP session for testing.
     */
    @POST
    public Response createSession(CreateDapSessionRequest request) {
        LOG.infof("Creating DAP session: workspace=%s, dapServerId=%s, name=%s",
                request.workspaceUri(), request.dapServerId(), request.sessionName());

        try {
            URI workspaceUri = URI.create(request.workspaceUri());

            DapSession session = sessionManager.createSession(
                    workspaceUri,
                    request.dapServerId(),
                    request.sessionName(),
                    OperationActor.USER // Created manually via REST API
            );

            // Return session info
            var response = Map.of(
                    "sessionId", session.getSessionId(),
                    "sessionName", session.getSessionName(),
                    "dapServerId", session.getServerConfig().getServerId(),
                    "state", session.getState().name(),
                    "language", session.getLanguage()
            );

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to create DAP session");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.fromException(e))
                    .build();
        }
    }

    /**
     * Launch a DAP session with the provided configuration.
     */
    @POST
    @Path("/{sessionId}/launch")
    public Response launchSession(
            @PathParam("sessionId") String sessionId,
            @QueryParam("debugMode") boolean debugMode,
            Map<String, Object> launchConfig) {

        LOG.infof("Launching DAP session: sessionId=%s, debugMode=%s, config=%s", sessionId, debugMode, launchConfig);

        try {
            DapSession session = sessionManager.getSession(sessionId);
            if (session == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Session not found: " + sessionId))
                        .build();
            }

            // Launch from Admin UI - create ProgressMonitor for user feedback
            ProgressMonitor progressMonitor = AdminProgressMonitorHelper.forDapSession(session);

            // Launch the session asynchronously (don't block HTTP thread!)
            session.launch(launchConfig, debugMode, OperationActor.USER, progressMonitor)
                    .whenComplete((result, error) -> {
                        progressMonitor.setComplete();
                        if (error != null) {
                            LOG.errorf(error, "DAP session launch failed: %s", sessionId);
                        } else {
                            LOG.infof("DAP session launched successfully: %s", sessionId);
                        }
                    });

            // Return immediately - client will get status updates via WebSocket
            return Response.accepted(Map.of(
                    "status", "launching",
                    "sessionId", sessionId,
                    "debugMode", debugMode,
                    "message", "Launch started, monitor status via WebSocket"
            )).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to start DAP session launch");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.fromException(e))
                    .build();
        }
    }

    /**
     * Stop a running DAP session.
     */
    @POST
    @Path("/{sessionId}/stop")
    public Response stopSession(@PathParam("sessionId") String sessionId) {
        LOG.infof("Stopping DAP session: sessionId=%s", sessionId);

        try {
            DapSession session = sessionManager.getSession(sessionId);
            if (session == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Session not found: " + sessionId))
                        .build();
            }

            // Terminate the session asynchronously
            session
                    .terminate()
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            LOG.errorf(error, "DAP session terminate failed: %s", sessionId);
                        } else {
                            LOG.infof("DAP session terminated successfully: %s", sessionId);
                        }
                    });

            // Return immediately
            return Response.accepted(Map.of(
                    "status", "stopping",
                    "sessionId", sessionId,
                    "message", "Stop requested"
            )).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to stop DAP session");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.fromException(e))
                    .build();
        }
    }

    /**
     * Delete a DAP session.
     */
    @DELETE
    @Path("/{sessionId}")
    public Response deleteSession(@PathParam("sessionId") String sessionId) {
        LOG.infof("Deleting DAP session: sessionId=%s", sessionId);

        try {
            DapSession session = sessionManager.getSession(sessionId);
            if (session == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Session not found: " + sessionId))
                        .build();
            }

            // Close session (terminate + remove + notify)
            sessionManager.closeSession(sessionId);

            return Response.ok(Map.of(
                    "status", "deleted",
                    "sessionId", sessionId
            )).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to delete DAP session");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    /**
     * Get launch configuration templates for a specific DAP server.
     * Templates are loaded from /dap/{serverId}/*.json resources.
     *
     * @param serverId DAP server ID (e.g., "vscode-js-debug")
     * @return List of template objects with "name", "label", and "body" fields
     */
    @GET
    @Path("/templates/{serverId}")
    public Response getLaunchConfigurationTemplates(@PathParam("serverId") String serverId) {
        LOG.infof("Getting launch configuration templates for serverId=%s", serverId);

        try {
            // Find the DAP server config
            DapServerConfig serverConfig = application.getDapServerConfig(serverId);
            if (serverConfig == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "DAP server not found: " + serverId))
                        .build();
            }

            // Get templates from the server config
            var templates = serverConfig.getConfigurationTemplates();

            return Response.ok(Map.of(
                    "serverId", serverId,
                    "templates", templates
            )).build();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to get launch configuration templates");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ErrorResponse.fromException(e))
                    .build();
        }
    }
}
