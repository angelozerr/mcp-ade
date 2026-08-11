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
package com.ibm.mcp.languagetools.admin.dto;

import io.quarkiverse.mcp.server.runtime.McpConnectionBase;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
public record McpClientDTO(
        String id,              // connectionId
        String name,            // client name (e.g., "claude-code")
        String version,         // client version
        String protocolVersion, // MCP protocol version
        String connectedAt      // ISO timestamp
) {

    public static McpClientDTO fromConnection(McpConnectionBase connection) {
        var initialRequest = connection.initialRequest();

        String name = "Unknown";
        String version = null;
        String protocolVersion = null;

        if (initialRequest != null) {
            if (initialRequest.implementation() != null) {
                name = initialRequest.implementation().name();
                version = initialRequest.implementation().version();
            }
            protocolVersion = initialRequest.protocolVersion().toString();
        }

        return new McpClientDTO(connection.id(), name, version, protocolVersion, null);
    }

    public static List<McpClientDTO> fromConnections(Iterable<McpConnectionBase> connections) {
        List<McpClientDTO> clients = new ArrayList<>();
        for (McpConnectionBase connection : connections) {
            clients.add(fromConnection(connection));
        }
        return clients;
    }
}
