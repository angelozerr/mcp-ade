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

import com.ibm.mcp.languagetools.admin.dto.McpClientDTO;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * REST API for MCP clients (AI connections).
 */
@Path("/api/admin/mcp/clients")
@Produces(MediaType.APPLICATION_JSON)
public class McpClientsResource {

    @Inject
    ConnectionManager connectionManager;

    @GET
    public List<McpClientDTO> getClients() {
        return McpClientDTO.fromConnections(connectionManager);
    }
}
