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

import com.fasterxml.jackson.annotation.JsonValue;

public enum WsMessageType {
    LSP_TRACE("lsp-trace"),
    DAP_TRACE("dap-trace"),
    BSP_TRACE("bsp-trace"),
    MCP_TRACE("mcp-trace"),
    PROGRESS_INIT("progress-init"),
    PROGRESS_UPDATE("progress-update"),
    SERVER_STATUS_CHANGED("server-status-changed"),
    WORKSPACES_UPDATE("workspaces-update"),
    MCP_CLIENTS_UPDATE("mcp-clients-update"),
    DAP_SESSION_UPDATE("dap-session-update"),
    TRACE_LEVEL_UPDATE("trace-level-update"),
    SERVER_ENABLED_CHANGED("server-enabled-changed"),
    OPERATION_UPDATE("operation-update"),
    ACTIVITY_STATE("activity-state");

    private final String label;

    WsMessageType(String label) {
        this.label = label;
    }

    @JsonValue
    public String getLabel() {
        return label;
    }
}
