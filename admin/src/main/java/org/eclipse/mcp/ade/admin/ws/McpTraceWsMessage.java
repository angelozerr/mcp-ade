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
package org.eclipse.mcp.ade.admin.ws;

public class McpTraceWsMessage extends TraceWsMessage {

    private final String connectionId;

    public McpTraceWsMessage(String connectionId, String content) {
        super(WsMessageType.MCP_TRACE, content, null);
        this.connectionId = connectionId;
    }

    public String getConnectionId() {
        return connectionId;
    }
}
