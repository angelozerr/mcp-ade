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
package org.eclipse.mcp.ade.command;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * REST endpoint for CLI command discovery and execution.
 * <p>
 * The {@code mcpade} CLI calls these endpoints to list available commands
 * and execute them.
 */
@Path("/api/commands")
@Produces(MediaType.APPLICATION_JSON)
public class CommandResource {

    @Inject
    CommandRegistry registry;

    /**
     * List all available commands with their metadata.
     * Used by {@code mcpade --help} to display available commands.
     */
    @GET
    public List<CommandInfo> listCommands() {
        return registry.listCommands();
    }

    /**
     * Execute a command by domain and action.
     * Used by {@code mcpade <domain> <action> [--args]}.
     */
    @POST
    @Path("/{domain}/{action}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response executeCommand(@PathParam("domain") String domain,
                                   @PathParam("action") String action,
                                   Map<String, String> args) {
        try {
            Object result = registry.execute(domain, action, args != null ? args : Map.of());
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", cause.getMessage()))
                    .build();
        }
    }
}
