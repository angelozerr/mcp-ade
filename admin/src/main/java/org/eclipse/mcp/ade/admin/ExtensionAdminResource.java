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

import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.admin.dto.ExtensionDTO;
import org.eclipse.mcp.ade.extension.Extension;
import org.eclipse.mcp.ade.extension.ExtensionRegistry;
import org.eclipse.mcp.ade.variable.VariableContext;
import org.eclipse.mcp.ade.variable.VariableResolverRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * REST endpoints for extension management.
 */
@Path("/api/admin/extensions")
@Produces(MediaType.APPLICATION_JSON)
public class ExtensionAdminResource {

    private static final Logger LOG = Logger.getLogger(ExtensionAdminResource.class);

    @Inject
    Application application;

    @Inject
    VariableResolverRegistry variableResolverRegistry;


    @GET
    public List<Map<String, Object>> listExtensions() {
        ExtensionRegistry registry = application.getExtensionRegistry();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Extension ext : registry.getExtensions()) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", ext.getId());
            if (ext.getName() != null) {
                dto.put("name", ext.getName());
            }
            if (ext.getDescription() != null) {
                dto.put("description", ext.getDescription());
            }
            dto.put("source", ext.getSource().name());
            if (registry.isExtensionEnabled(ext.getId())) {
                dto.put("enabled", true);
            }
            int lspCount = ext.getLspServerConfigs().size();
            int dapCount = ext.getDapServerConfigs().size();
            int bspCount = ext.getBspServerConfigs().size();
            if (lspCount > 0) dto.put("lspCount", lspCount);
            if (dapCount > 0) dto.put("dapCount", dapCount);
            if (bspCount > 0) dto.put("bspCount", bspCount);
            result.add(dto);
        }
        return result;
    }

    @GET
    @Path("/{id}")
    public Response getExtension(@PathParam("id") String id) {
        ExtensionRegistry registry = application.getExtensionRegistry();
        Extension ext = registry.getExtension(id);
        if (ext == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Extension '" + id + "' not found"))
                    .build();
        }
        return Response.ok(ExtensionDTO.fromExtension(ext, registry)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addExtension(Map<String, String> body) {
        String extensionId = body.get("extensionId");
        String source = body.get("source");

        if (extensionId == null || source == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "extensionId and source are required"))
                    .build();
        }

        try {
            ExtensionRegistry registry = application.getExtensionRegistry();
            Extension ext = registry.addExtension(extensionId, Paths.get(source), application);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("extension", ExtensionDTO.fromExtension(ext, registry));
            return Response.status(Response.Status.CREATED).entity(result).build();
        } catch (Exception e) {
            LOG.error("Failed to add extension", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadExtension(@RestForm("extensionId") String extensionId,
                                    @RestForm("file") FileUpload file) {
        if (extensionId == null || extensionId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "extensionId is required"))
                    .build();
        }
        if (file == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "file is required"))
                    .build();
        }

        java.nio.file.Path uploadedPath = file.uploadedFile();
        String fileName = file.fileName();
        java.nio.file.Path renamedPath = null;
        try {
            renamedPath = uploadedPath.getParent().resolve(fileName);
            Files.move(uploadedPath, renamedPath);

            ExtensionRegistry registry = application.getExtensionRegistry();
            Extension ext = registry.addExtension(extensionId, renamedPath, application);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("extension", ExtensionDTO.fromExtension(ext, registry));
            return Response.status(Response.Status.CREATED).entity(result).build();
        } catch (Exception e) {
            LOG.error("Failed to upload extension", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } finally {
            try {
                if (renamedPath != null && Files.exists(renamedPath)) {
                    Files.deleteIfExists(renamedPath);
                }
                Files.deleteIfExists(uploadedPath);
            } catch (IOException ignored) {
            }
        }
    }

    @POST
    @Path("/server/json")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addServerFromJson(Map<String, String> body) {
        String serverType = body.get("serverType");
        String serverJson = body.get("serverJson");
        String extensionId = body.get("extensionId");

        if (serverType == null || serverJson == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "serverType and serverJson are required"))
                    .build();
        }

        if (!List.of("lsp", "dap", "bsp").contains(serverType)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "serverType must be lsp, dap, or bsp"))
                    .build();
        }

        try {
            ExtensionRegistry registry = application.getExtensionRegistry();
            Extension ext = registry.addServerFromJson(serverType, serverJson, extensionId, application);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("extension", ExtensionDTO.fromExtension(ext, registry));
            return Response.status(Response.Status.CREATED).entity(result).build();
        } catch (Exception e) {
            LOG.error("Failed to add server from JSON", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/resolve-variables")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response resolveVariables(Map<String, String> body) {
        Map<String, String> resolved = new LinkedHashMap<>();
        VariableContext ctx = VariableContext.empty();
        for (Map.Entry<String, String> entry : body.entrySet()) {
            resolved.put(entry.getKey(), variableResolverRegistry.resolve(entry.getValue(), ctx));
        }
        return Response.ok(resolved).build();
    }

    @DELETE
    @Path("/{id}")
    public Response removeExtension(@PathParam("id") String id) {
        try {
            application.getExtensionRegistry().removeExtension(id);
            return Response.ok(Map.of("success", true, "message", "Extension '" + id + "' removed")).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/{id}/enable")
    public Response enableExtension(@PathParam("id") String id) {
        try {
            application.getExtensionRegistry().enableExtension(id);
            return Response.ok(Map.of("success", true, "message", "Extension '" + id + "' enabled")).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/{id}/disable")
    public Response disableExtension(@PathParam("id") String id) {
        try {
            application.getExtensionRegistry().disableExtension(id);
            return Response.ok(Map.of("success", true, "message", "Extension '" + id + "' disabled")).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    // ========== Individual server enable/disable ==========

    @POST
    @Path("/lsp/servers/{serverId}/enable")
    public Response enableLspServer(@PathParam("serverId") String serverId) {
        application.enableServer(serverId);
        return Response.ok(Map.of("success", true, "message", "LSP server '" + serverId + "' enabled")).build();
    }

    @POST
    @Path("/lsp/servers/{serverId}/disable")
    public Response disableLspServer(@PathParam("serverId") String serverId) {
        application.disableLspServer(serverId);
        return Response.ok(Map.of("success", true, "message", "LSP server '" + serverId + "' disabled")).build();
    }

    @POST
    @Path("/dap/servers/{serverId}/enable")
    public Response enableDapServer(@PathParam("serverId") String serverId) {
        application.enableServer(serverId);
        return Response.ok(Map.of("success", true, "message", "DAP server '" + serverId + "' enabled")).build();
    }

    @POST
    @Path("/dap/servers/{serverId}/disable")
    public Response disableDapServer(@PathParam("serverId") String serverId) {
        application.disableDapServer(serverId);
        return Response.ok(Map.of("success", true, "message", "DAP server '" + serverId + "' disabled")).build();
    }

}
