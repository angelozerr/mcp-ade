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
package com.ibm.mcp.languagetools.admin.ws;

import com.ibm.mcp.languagetools.trace.TraceCollector;

public class BspTraceWsMessage extends TraceWsMessage {

    private final String workspaceUri;
    private final String serverId;

    public BspTraceWsMessage(String workspaceUri, String serverId, String content, TraceCollector.MessageType messageType) {
        super(WsMessageType.BSP_TRACE, content, messageType);
        this.workspaceUri = workspaceUri;
        this.serverId = serverId;
    }

    public String getWorkspaceUri() {
        return workspaceUri;
    }

    public String getServerId() {
        return serverId;
    }
}
