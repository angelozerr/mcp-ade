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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ibm.mcp.languagetools.trace.TraceCollector;

public class ServerTraceWsMessage extends TraceWsMessage {

    private final String workspaceUri;
    private final String serverId;
    private final String sessionId;

    public ServerTraceWsMessage(WsMessageType type, String workspaceUri, String serverId, String content, TraceCollector.MessageType messageType) {
        this(type, workspaceUri, serverId, null, content, messageType);
    }

    public ServerTraceWsMessage(WsMessageType type, String workspaceUri, String serverId, String sessionId, String content, TraceCollector.MessageType messageType) {
        super(type, content, messageType);
        this.workspaceUri = workspaceUri;
        this.serverId = serverId;
        this.sessionId = sessionId;
    }

    public String getWorkspaceUri() {
        return workspaceUri;
    }

    public String getServerId() {
        return serverId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getSessionId() {
        return sessionId;
    }
}
