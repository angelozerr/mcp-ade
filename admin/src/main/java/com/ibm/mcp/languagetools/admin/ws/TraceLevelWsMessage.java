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

public class TraceLevelWsMessage extends WsMessage {

    private final String serverType;
    private final String serverId;
    private final String traceLevel;
    private final String workspaceUri;

    public TraceLevelWsMessage(String serverType, String serverId, String traceLevel) {
        this(serverType, serverId, traceLevel, null);
    }

    public TraceLevelWsMessage(String serverType, String serverId, String traceLevel, String workspaceUri) {
        super(WsMessageType.TRACE_LEVEL_UPDATE);
        this.serverType = serverType;
        this.serverId = serverId;
        this.traceLevel = traceLevel;
        this.workspaceUri = workspaceUri;
    }

    public String getServerType() {
        return serverType;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getServerId() {
        return serverId;
    }

    public String getTraceLevel() {
        return traceLevel;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getWorkspaceUri() {
        return workspaceUri;
    }
}
