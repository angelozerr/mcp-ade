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
import com.ibm.mcp.languagetools.runtime.RuntimeConfig;
import com.ibm.mcp.languagetools.runtime.RuntimeRegistry;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
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

    /**
     * List all registered runtimes with their status and dependent servers.
     * Triggers async checks for unchecked runtimes — results arrive via WebSocket.
     */
    @GET
    public List<Map<String, Object>> listRuntimes() {
        runtimeRegistry.checkUnchecked();
        List<Map<String, Object>> result = new ArrayList<>();

        for (RuntimeConfig runtime : runtimeRegistry.getAll().values()) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", runtime.getRuntimeId());
            dto.put("name", runtime.getName());
            dto.put("description", runtime.getDescription());
            dto.put("url", runtime.getUrl());
            dto.put("autoInstallable", runtime.isAutoInstallable());
            String statusName = runtime.isChecking() ? "CHECKING" : runtime.getStatus().name();
            dto.put("status", statusName);

            String error = runtime.getLastInstallError();
            if (error != null) {
                dto.put("error", error);
            }

            // Dependent servers grouped by type
            Map<String, List<String>> dependents = new LinkedHashMap<>();
            for (ServerConfigBase server : runtime.getDependentServers()) {
                String type = getServerType(server);
                dependents.computeIfAbsent(type, k -> new ArrayList<>())
                        .add(server.getServerId());
            }
            dto.put("dependentServers", dependents);

            if (runtime.getExtensionId() != null) {
                dto.put("extensionId", runtime.getExtensionId());
            }

            result.add(dto);
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

        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", runtime.getRuntimeId());
        dto.put("name", runtime.getName());
        dto.put("description", runtime.getDescription());
        dto.put("url", runtime.getUrl());
        dto.put("autoInstallable", runtime.isAutoInstallable());
        dto.put("status", runtime.getStatus().name());

        String error = runtime.getLastInstallError();
        if (error != null) {
            dto.put("error", error);
        }

        // Dependent servers grouped by type (same structure as list endpoint)
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (ServerConfigBase server : runtime.getDependentServers()) {
            String type = getServerType(server);
            dependents.computeIfAbsent(type, k -> new ArrayList<>())
                    .add(server.getServerId());
        }
        dto.put("dependentServers", dependents);

        return Response.ok(dto).build();
    }

    /**
     * Install a runtime.
     */
    @POST
    @Path("/{runtimeId}/install")
    public Response installRuntime(@PathParam("runtimeId") String runtimeId) {
        RuntimeConfig runtime = runtimeRegistry.get(runtimeId);
        if (runtime == null) {
            return Response.status(404).entity(new ErrorResponse("Runtime not found: " + runtimeId)).build();
        }

        if (!runtime.isAutoInstallable()) {
            return Response.status(400)
                    .entity(new ErrorResponse("Runtime '" + runtimeId + "' requires manual installation. Visit: " + runtime.getUrl()))
                    .build();
        }

        runtimeRegistry.installRuntimeAsync(runtime);

        return Response.accepted().entity(new StatusResponse("installing")).build();
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
