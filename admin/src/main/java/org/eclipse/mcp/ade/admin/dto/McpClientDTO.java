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
package org.eclipse.mcp.ade.admin.dto;

import org.eclipse.mcp.ade.mcp.McpClientTracker;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

@RegisterForReflection
public record McpClientDTO(
        String id,              // connectionId
        String name,            // client name (e.g., "claude-code")
        String version,         // client version
        String protocolVersion, // MCP protocol version
        String connectedAt      // ISO timestamp
) {

    public static McpClientDTO fromTrackedClient(McpClientTracker.TrackedClient tracked) {
        return new McpClientDTO(
                tracked.lastConnectionId,
                tracked.name,
                tracked.version,
                tracked.protocolVersion,
                DateTimeFormatter.ISO_INSTANT.format(tracked.firstSeen)
        );
    }

    public static List<McpClientDTO> fromTrackedClients(Collection<McpClientTracker.TrackedClient> tracked) {
        return tracked.stream()
                .map(McpClientDTO::fromTrackedClient)
                .toList();
    }
}
