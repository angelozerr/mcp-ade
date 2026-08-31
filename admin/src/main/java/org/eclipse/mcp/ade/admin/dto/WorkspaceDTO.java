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

import org.eclipse.mcp.ade.workspace.Workspace;

import java.time.format.DateTimeFormatter;
import java.util.List;

public record WorkspaceDTO(
    String rootUri,
    List<McpClientInfo> mcpClients,
    boolean fileWatcherEnabled,
    String fileWatcherEnabledSource,
    boolean fileWatcherRunning,
    String fileWatcherStatus,
    String fileWatcherFailureReason
) {
    public record McpClientInfo(
        String name,
        String connectedAt
    ) {}

    public static WorkspaceDTO fromWorkspace(Workspace workspace) {
        List<McpClientInfo> mcpClients = workspace.getMcpClientConnections().values().stream()
                .map(clientInfo -> new McpClientInfo(
                        clientInfo.name(),
                        DateTimeFormatter.ISO_INSTANT.format(clientInfo.connectedAt())
                ))
                .toList();

        var uri = workspace.getNormalizedUri();
        var fwResolved = workspace.getWorkspaceConfiguration().resolveBoolean("fileWatchers.enabled", true);
        return new WorkspaceDTO(uri, mcpClients, fwResolved.value(), fwResolved.source().name(),
                workspace.isFileWatcherRunning(),
                workspace.getFileWatcherStatus().name(),
                workspace.getFileWatcherFailureReason());
    }
}
