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

import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.admin.dto.ErrorResponse;
import org.eclipse.mcp.ade.admin.dto.StatusResponse;
import org.eclipse.mcp.ade.installer.TaskRegistryInstaller;
import org.eclipse.mcp.ade.installer.TraceProgressMonitor;
import org.eclipse.mcp.ade.progress.ProgressBroadcaster;
import org.eclipse.mcp.ade.progress.ProgressStep;
import org.eclipse.mcp.ade.trace.TraceCollector;
import org.eclipse.mcp.ade.installer.InstallableConfig;
import org.eclipse.mcp.ade.installer.InstallationStatus;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import org.eclipse.mcp.ade.workspace.Workspace;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.Collection;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.nio.file.Files;

import static org.eclipse.mcp.ade.utils.JsonUtils.getPrettyPrintGson;

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

    @Inject
    Event<InstallStatusChangeEvent> installStatusEvent;

    protected void checkUncheckedServers(Collection<? extends InstallableConfig> configs) {
        for (InstallableConfig config : configs) {
            if (config.getInstaller() != null && config.getInstaller().getStatus() == InstallationStatus.NOT_INSTALLED && !config.isChecking()) {
                config.checkInstalled()
                        .whenComplete((result, error) -> {
                            String status = config.getStatus().name();
                            installStatusEvent.fire(new InstallStatusChangeEvent(config.getServerId(), status));
                        });
            }
        }
    }

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

    /**
     * Get the trace collector for this server type from the application.
     */
    protected abstract TraceCollector getTraceCollector();

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
        if (config.getTraceCollector() == null) {
            config.setTraceCollector(getTraceCollector());
        }

        String taskId = "install-" + serverId;
        String title = "Installing " + (config.getName() != null ? config.getName() : serverId);
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
                        progressMonitor.setComplete(result != null ? result.getCommand() : null);
                    }
                });

        return Response.ok().entity(new StatusResponse("installing")).build();
    }

    /**
     * Cancel a progress task (e.g., an installation).
     * Only works for tasks marked as cancellable (Admin-initiated tasks).
     */
    @POST
    @Path("/progress/{taskId}/cancel")
    public Response cancelTask(@PathParam("taskId") String taskId) {
        String serverId = taskId.replaceFirst("^(install|start|restart)-", "");

        var config = getServerConfig(serverId);
        if (config == null) {
            return Response.status(404).entity(new ErrorResponse("Server not found: " + serverId)).build();
        }

        var sharedProgress = config.getSharedInstallProgress();
        if (sharedProgress == null) {
            return Response.status(404).entity(new ErrorResponse("No active task to cancel")).build();
        }

        LOG.infof("Cancelling task '%s' for server '%s'", taskId, serverId);
        sharedProgress.cancel(taskId);

        return Response.ok().entity(new StatusResponse("cancelled")).build();
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
