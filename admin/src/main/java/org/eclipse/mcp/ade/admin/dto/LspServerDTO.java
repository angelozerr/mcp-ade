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

import org.eclipse.mcp.ade.server.ServerStatus;

/**
 * Runtime state of an LSP server in a specific workspace.
 * This represents the dynamic state that changes during execution.
 */
public record LspServerDTO(
    String serverId,
    ServerStatus status,
    String statusMessage,
    boolean isReady,
    Long pid,
    String command,
    ExternalInstanceInfo externalInstance,
    String parentServerId,  // For extensions: the server they extend (null for normal servers)
    Double installProgress,  // Install progress (0.0-1.0) when status is INSTALLING, null otherwise
    String traceLevel
) {
    /**
     * Information about an external LSP server instance (launched by an IDE).
     */
    public record ExternalInstanceInfo(
        int port,
        long pid,
        boolean isAlive,
        String clientName,
        String clientVersion
    ) {}
}
