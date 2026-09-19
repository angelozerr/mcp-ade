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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/admin/progress")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ProgressAdminResource {

    @Inject
    AdminProgressBroadcaster broadcaster;

    @POST
    @Path("/{taskId}/cancel")
    public Response cancelTask(@PathParam("taskId") String taskId) {
        if (broadcaster.cancelTask(taskId)) {
            return Response.ok().entity(new StatusResponse("cancelled")).build();
        }
        return Response.status(404).entity(new ErrorResponse("Task not found or not cancellable: " + taskId)).build();
    }
}
