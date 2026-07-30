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

import com.google.gson.JsonParser;
import com.ibm.mcp.languagetools.configuration.ApplicationConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
@Path("/api/admin/settings")
@Produces(MediaType.APPLICATION_JSON)
public class SettingsAdminResource {

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @GET
    @Path("/{key}")
    public Response getSetting(@PathParam("key") String key) {
        String value = applicationConfiguration.getString(key);
        if (value == null) {
            return Response.status(404)
                    .entity("{\"error\": \"Setting not found: " + key + "\"}")
                    .build();
        }
        return Response.ok("{\"key\": \"" + key + "\", \"value\": \"" + value + "\"}").build();
    }

    @PUT
    @Path("/{key}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setSetting(@PathParam("key") String key, String body) {
        try {
            String value = JsonParser.parseString(body)
                    .getAsJsonObject()
                    .get("value")
                    .getAsString();
            applicationConfiguration.set(key, value);
            return Response.noContent().build();
        } catch (Exception e) {
            return Response.status(400)
                    .entity("{\"error\": \"" + e.getMessage() + "\"}")
                    .build();
        }
    }
}
