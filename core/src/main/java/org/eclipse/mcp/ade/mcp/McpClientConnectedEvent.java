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
package org.eclipse.mcp.ade.mcp;

/**
 * CDI event fired when an MCP client connects.
 */
public class McpClientConnectedEvent {

    private final String clientName;
    private final String connectionId;

    public McpClientConnectedEvent(String clientName, String connectionId) {
        this.clientName = clientName;
        this.connectionId = connectionId;
    }

    public String getClientName() {
        return clientName;
    }

    public String getConnectionId() {
        return connectionId;
    }
}
