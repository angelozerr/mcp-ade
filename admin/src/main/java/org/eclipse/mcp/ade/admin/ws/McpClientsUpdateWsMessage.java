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

import org.eclipse.mcp.ade.admin.dto.McpClientDTO;

import java.util.List;

public class McpClientsUpdateWsMessage extends WsMessage {

    private final List<McpClientDTO> clients;

    public McpClientsUpdateWsMessage(List<McpClientDTO> clients) {
        super(WsMessageType.MCP_CLIENTS_UPDATE);
        this.clients = clients;
    }

    public List<McpClientDTO> getClients() { return clients; }
}
