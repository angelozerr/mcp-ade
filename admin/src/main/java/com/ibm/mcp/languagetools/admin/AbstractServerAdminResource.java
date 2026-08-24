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

import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.admin.dto.ErrorResponse;
import com.ibm.mcp.languagetools.admin.dto.StatusResponse;
import com.ibm.mcp.languagetools.installer.TaskRegistryInstaller;
import com.ibm.mcp.languagetools.installer.TraceProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressBroadcaster;
import com.ibm.mcp.languagetools.progress.ProgressStep;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.workspace.Workspace;
import jakarta.inject.Inject;

import java.net.URI;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.file.Files;

import static com.ibm.mcp.languagetools.utils.JsonUtils.getPrettyPrintGson;

/**
 * Abstract base class for server admin resources (LSP and DAP).
 * Contains shared installer operations: get, save, and run installer.
 */
public abstract class AbstractServerAdminResource {

    private static final Logger LOG = Logger.getLogger(AbstractServerAdminResource.class);

    @Inject
    protected Application application;

    @Inject
    protected ProgressBroadcaster progressBroadcaster;

    /**
     * Get the server configuration by ID.
     *
     * @param serverId the server identifier
     * @return the server config, or null if not found
     */
    protected abstract ServerConfigBase getServerConfig(String serverId);

    /**
     * Get the server type name for error messages (e.g. "LSP" or "DAP").
     */
    protected abstract String getServerType();

    // ========== Installer Operations ==========

    /**
     * Get installer.json for a server.
     */
    @GET
    @Path("/configs/{serverId}/installer")
    public Response getInstaller(@PathParam("serverId") String serverId) {
        ServerConfigBase config = getServerConfig(serverId);

        if (config == null) {
            throw new NotFoundException(getServerType() + " server not found: " + serverId);
        }

        var installerConfig = config.getInstallerConfig();
        if (installerConfig == null) {
            return Response.ok("{}").build();
        }

        return Response.ok(getPrettyPrintGson().toJson(installerConfig)).build();
    }

    /**
     * Save installer.json for a server.
     */
    @POST
    @Path("/configs/{serverId}/installer")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveInstaller(@PathParam("serverId") String serverId, JsonObject installerJson) {
        ServerConfigBase config = getServerConfig(serverId);

        if (config == null) {
            throw new NotFoundException(getServerType() + " server not found: " + serverId);
        }

        try {
            var serverHome = config.getServerHome();
            Files.createDirectories(serverHome);
            var installerPath = serverHome.resolve("installer.json");
            String json = getPrettyPrintGson().toJson(installerJson);
            Files.writeString(installerPath, json);

            return Response.ok().build();
        } catch (Exception e) {
            LOG.errorf("Failed to save installer.json for %s: %s", serverId, e.getMessage());
            return Response.status(500)
                    .entity(new ErrorResponse("Failed to save installer.json: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Run installer for a server.
     */
    @POST
    @Path("/configs/{serverId}/install")
    public Response runInstaller(@PathParam("serverId") String serverId,
                                 @QueryParam("force") @DefaultValue("false") boolean force,
                                 @QueryParam("workspaceUri") String workspaceUriParam) {
        ServerConfigBase config = getServerConfig(serverId);
        if (config == null) {
            throw new NotFoundException(getServerType() + " server not found: " + serverId);
        }
        if (config.getInstaller() == null) {
            return Response.status(404).entity(new ErrorResponse("No installer configured for: " + serverId)).build();
        }

        Workspace workspace = null;
        if (workspaceUriParam != null) {
            workspace = application.getWorkspace(URI.create(workspaceUriParam));
        }
        if (workspace == null) {
            var workspaces = application.getWorkspaces();
            if (!workspaces.isEmpty()) {
                workspace = workspaces.iterator().next();
            }
        }
        if (workspace == null) {
            return Response.status(400).entity(new ErrorResponse("No workspace available for installation")).build();
        }

        String taskId = "install-" + serverId;
        String title = "Install " + serverId;
        TraceProgressMonitor progressMonitor = new TraceProgressMonitor(
                config.getTraceCollector(), 100.0, progressBroadcaster, taskId, serverId, title);
        TaskRegistryInstaller.configureInstallerSteps(progressMonitor, config.getInstallerConfig(), force);
        progressMonitor.initializeSteps();

        config.resetInstallState();
        config.ensureInstalled(workspace, null, progressMonitor, force)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                        LOG.errorf(ex, "Failed to install server '%s'", serverId);
                        progressMonitor.setFailed(cause.getMessage());
                    } else {
                        progressMonitor.setComplete();
                    }
                });

        return Response.ok().entity(new StatusResponse("installing")).build();
    }

    /**
     * Create a TraceProgressMonitor with standard server startup steps
     * (Installing -> Starting -> Initializing).
     */
    protected TraceProgressMonitor createServerStartMonitor(
            TraceCollector traceCollector,
            String taskId, String serverId, String title) {
        TraceProgressMonitor monitor = new TraceProgressMonitor(
                traceCollector, 100.0, progressBroadcaster, taskId, serverId, title);
        monitor.addStep(ProgressStep.INSTALLING_RUNTIME, 0.20);
        monitor.addStep(ProgressStep.INSTALLING, 0.30);
        monitor.addStep(ProgressStep.STARTING, 0.10);
        monitor.addStep(ProgressStep.INITIALIZING, 0.40);
        monitor.initializeSteps();
        return monitor;
    }
}
