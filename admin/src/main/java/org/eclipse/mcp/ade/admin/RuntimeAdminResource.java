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
import org.eclipse.mcp.ade.installer.TaskRegistryInstaller;
import org.eclipse.mcp.ade.installer.TraceProgressMonitor;
import org.eclipse.mcp.ade.progress.ProgressBroadcaster;
import org.eclipse.mcp.ade.runtime.RuntimeConfig;
import org.eclipse.mcp.ade.runtime.RuntimeRegistry;
import org.eclipse.mcp.ade.runtime.RuntimeSource;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoint for runtime admin operations.
 */
@Path("/api/admin/runtimes")
@Produces(MediaType.APPLICATION_JSON)
public class RuntimeAdminResource {

    private static final Logger LOG = Logger.getLogger(RuntimeAdminResource.class);

    @Inject
    RuntimeRegistry runtimeRegistry;

    @Inject
    ProgressBroadcaster progressBroadcaster;

    /**
     * List all registered runtimes with their status and dependent servers.
     * Triggers async checks for unchecked runtimes — results arrive via WebSocket.
     */
    @GET
    public List<Map<String, Object>> listRuntimes() {
        runtimeRegistry.checkUnchecked();
        List<Map<String, Object>> result = new ArrayList<>();
        for (RuntimeConfig runtime : runtimeRegistry.getAll().values()) {
            result.add(buildRuntimeSummaryDto(runtime));
        }
        return result;
    }

    /**
     * Get details of a specific runtime.
     */
    @GET
    @Path("/{runtimeId}")
    public Response getRuntime(@PathParam("runtimeId") String runtimeId) {
        RuntimeConfig runtime = runtimeRegistry.get(runtimeId);
        if (runtime == null) {
            return Response.status(404).entity(new ErrorResponse("Runtime not found: " + runtimeId)).build();
        }
        return Response.ok(buildRuntimeDto(runtime)).build();
    }

    private Map<String, Object> buildRuntimeSummaryDto(RuntimeConfig runtime) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", runtime.getRuntimeId());
        dto.put("name", runtime.getName());
        if (runtime.isAutoInstallable()) {
            dto.put("autoInstallable", true);
        }
        String statusName = runtime.isChecking() ? "CHECKING" : runtime.getStatus().name();
        dto.put("status", statusName);

        String error = runtime.getLastInstallError();
        if (error != null) {
            dto.put("error", error);
        }

        String activeSource = runtime.getActiveSource() != null ? runtime.getActiveSource().name() : null;
        if (activeSource != null) {
            dto.put("activeSource", activeSource);
        }

        int dependentCount = 0;
        for (ServerConfigBase server : runtime.getDependentServers()) {
            dependentCount++;
        }
        if (dependentCount > 0) {
            dto.put("dependentServerCount", dependentCount);
        }

        return dto;
    }

    private Map<String, Object> buildRuntimeDto(RuntimeConfig runtime) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", runtime.getRuntimeId());
        dto.put("name", runtime.getName());
        if (runtime.getDescription() != null) {
            dto.put("description", runtime.getDescription());
        }
        if (runtime.getUrl() != null) {
            dto.put("url", runtime.getUrl());
        }
        if (runtime.isAutoInstallable()) {
            dto.put("autoInstallable", true);
        }
        String statusName = runtime.isChecking() ? "CHECKING" : runtime.getStatus().name();
        dto.put("status", statusName);

        String error = runtime.getLastInstallError();
        if (error != null) {
            dto.put("error", error);
        }

        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (ServerConfigBase server : runtime.getDependentServers()) {
            String type = getServerType(server);
            dependents.computeIfAbsent(type, k -> new ArrayList<>())
                    .add(server.getServerId());
        }
        if (!dependents.isEmpty()) {
            dto.put("dependentServers", dependents);
        }

        if (runtime.getExtensionId() != null) {
            dto.put("extensionId", runtime.getExtensionId());
            if (runtime.getExtensionName() != null) {
                dto.put("extensionName", runtime.getExtensionName());
            }
        }

        if (runtime.getResolvedPath() != null) {
            dto.put("resolvedPath", runtime.getResolvedPath());
        }
        String activeSource = runtime.getActiveSource() != null ? runtime.getActiveSource().name() : null;
        if (activeSource != null) {
            dto.put("activeSource", activeSource);
        }
        dto.put("sourceMode", runtime.getSourceMode().name());
        if (runtime.isFallbackUsed()) {
            dto.put("fallbackUsed", true);
        }

        return dto;
    }

    /**
     * Install a runtime with progress monitoring (same pattern as server install).
     */
    @POST
    @Path("/{runtimeId}/install")
    public Response installRuntime(@PathParam("runtimeId") String runtimeId,
                                    @QueryParam("force") @DefaultValue("true") boolean force) {
        RuntimeConfig runtime = runtimeRegistry.get(runtimeId);
        if (runtime == null) {
            return Response.status(404).entity(new ErrorResponse("Runtime not found: " + runtimeId)).build();
        }

        if (!runtime.isAutoInstallable()) {
            return Response.status(400)
                    .entity(new ErrorResponse("Runtime '" + runtimeId + "' requires manual installation. Visit: " + runtime.getUrl()))
                    .build();
        }

        String taskId = "install-" + runtimeId;
        String title = "Installing " + (runtime.getName() != null ? runtime.getName() : runtimeId);
        TraceProgressMonitor progressMonitor = new TraceProgressMonitor(
                runtime.getTraceCollector(), 100.0, progressBroadcaster, taskId, runtimeId, title);
        TaskRegistryInstaller.configureInstallerSteps(progressMonitor, runtime.getInstallerConfig(), force);
        progressMonitor.initializeSteps();

        runtimeRegistry.installRuntimeAsync(runtime, progressMonitor);

        return Response.accepted().entity(new StatusResponse("installing")).build();
    }

    /**
     * Cancel a progress task (e.g., a runtime installation).
     */
    @POST
    @Path("/progress/{taskId}/cancel")
    public Response cancelTask(@PathParam("taskId") String taskId) {
        String runtimeId = taskId.replaceFirst("^install-", "");
        RuntimeConfig runtime = runtimeRegistry.get(runtimeId);
        if (runtime == null) {
            return Response.status(404).entity(new ErrorResponse("Runtime not found: " + runtimeId)).build();
        }

        var sharedProgress = runtime.getSharedInstallProgress();
        if (sharedProgress == null) {
            return Response.status(404).entity(new ErrorResponse("No active task to cancel")).build();
        }

        LOG.infof("Cancelling task '%s' for runtime '%s'", taskId, runtimeId);
        sharedProgress.cancel(taskId);

        return Response.ok().entity(new StatusResponse("cancelled")).build();
    }

    /**
     * Check runtime status (re-check if installed).
     */
    @POST
    @Path("/{runtimeId}/check")
    public Response checkRuntime(@PathParam("runtimeId") String runtimeId) {
        RuntimeConfig runtime = runtimeRegistry.get(runtimeId);
        if (runtime == null) {
            return Response.status(404).entity(new ErrorResponse("Runtime not found: " + runtimeId)).build();
        }

        runtime.resetInstallState();
        runtimeRegistry.checkRuntimeAsync(runtime);

        return Response.accepted().entity(new StatusResponse("checking")).build();
    }

    /**
     * Change the source mode for a runtime (AUTO, SYSTEM, EMBEDDED).
     */
    @PUT
    @Path("/{runtimeId}/source-mode")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setSourceMode(@PathParam("runtimeId") String runtimeId, Map<String, String> body) {
        RuntimeConfig runtime = runtimeRegistry.get(runtimeId);
        if (runtime == null) {
            return Response.status(404).entity(new ErrorResponse("Runtime not found: " + runtimeId)).build();
        }
        String value = body.get("sourceMode");
        RuntimeSource pref = RuntimeSource.fromValue(value);
        runtimeRegistry.setSourceMode(runtimeId, pref);
        return Response.accepted().entity(new StatusResponse(pref.name())).build();
    }

    private String getServerType(ServerConfigBase server) {
        String className = server.getClass().getSimpleName();
        if (className.contains("Lsp")) return "lsp";
        if (className.contains("Dap")) return "dap";
        if (className.contains("Bsp")) return "bsp";
        // Fallback: check the resource path
        String basePath = server.getResourceBasePath();
        if (basePath.startsWith("/lsp")) return "lsp";
        if (basePath.startsWith("/dap")) return "dap";
        if (basePath.startsWith("/bsp")) return "bsp";
        return "unknown";
    }
}
