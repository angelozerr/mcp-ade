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

import com.google.gson.JsonParser;
import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.admin.ws.TraceLevelWsMessage;
import org.eclipse.mcp.ade.configuration.ApplicationConfiguration;
import org.eclipse.mcp.ade.configuration.ServerTrace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/traces")
@Produces(MediaType.APPLICATION_JSON)
public class TraceResource {

    @Inject
    Application application;

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @Inject
    Event<TraceLevelWsMessage> traceLevelEvent;

    // ========== Clear Traces ==========

    @DELETE
    @Path("/lsp")
    public void clearLspTraces() {
        application.getLspTraceCollector().clear();
    }

    @DELETE
    @Path("/dap")
    public void clearDapTraces() {
        application.getDapTraceCollector().clear();
    }

    @DELETE
    @Path("/mcp")
    public void clearMcpTraces() {
        application.getMcpTraceCollector().clear();
    }

    // ========== Clear BSP Traces ==========

    @DELETE
    @Path("/bsp")
    public void clearBspTraces() {
        application.getBspTraceCollector().clear();
    }

    // ========== LSP Trace Level ==========

    @PUT
    @Path("/lsp/{serverId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setLspTraceLevel(@PathParam("serverId") String serverId, String body) {
        try {
            ServerTrace level = parseTraceLevel(body);
            applicationConfiguration.setLspTraceLevel(serverId, level);
            traceLevelEvent.fire(new TraceLevelWsMessage("lsp", serverId, level.toString()));
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(400)
                    .entity(Map.of("error", String.valueOf(e.getMessage())))
                    .build();
        }
    }

    // ========== DAP Trace Level ==========

    @PUT
    @Path("/dap/{serverId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setDapTraceLevel(@PathParam("serverId") String serverId, String body) {
        try {
            ServerTrace level = parseTraceLevel(body);
            applicationConfiguration.setDapTraceLevel(serverId, level);
            traceLevelEvent.fire(new TraceLevelWsMessage("dap", serverId, level.toString()));
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(400)
                    .entity(Map.of("error", String.valueOf(e.getMessage())))
                    .build();
        }
    }

    // ========== BSP Trace Level ==========

    @PUT
    @Path("/bsp/{serverId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setBspTraceLevel(@PathParam("serverId") String serverId, String body) {
        try {
            ServerTrace level = parseTraceLevel(body);
            applicationConfiguration.setBspTraceLevel(serverId, level);
            traceLevelEvent.fire(new TraceLevelWsMessage("bsp", serverId, level.toString()));
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(400)
                    .entity(Map.of("error", String.valueOf(e.getMessage())))
                    .build();
        }
    }

    // ========== MCP Trace Level ==========

    @PUT
    @Path("/mcp")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setMcpTraceLevel(String body) {
        try {
            ServerTrace level = parseTraceLevel(body);
            applicationConfiguration.setMcpTraceLevel(level);
            traceLevelEvent.fire(new TraceLevelWsMessage("mcp", null, level.toString()));
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(400)
                    .entity(Map.of("error", String.valueOf(e.getMessage())))
                    .build();
        }
    }

    // ========== Helper ==========

    private ServerTrace parseTraceLevel(String body) {
        String traceLevel = JsonParser.parseString(body)
                .getAsJsonObject()
                .get("traceLevel")
                .getAsString();
        return ServerTrace.fromValue(traceLevel);
    }
}
