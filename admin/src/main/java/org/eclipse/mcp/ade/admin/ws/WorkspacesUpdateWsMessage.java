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

import org.eclipse.mcp.ade.admin.dto.WorkspaceDTO;

import java.util.List;

public class WorkspacesUpdateWsMessage extends WsMessage {

    private final List<WorkspaceDTO> workspaces;

    public WorkspacesUpdateWsMessage(List<WorkspaceDTO> workspaces) {
        super(WsMessageType.WORKSPACES_UPDATE);
        this.workspaces = workspaces;
    }

    public List<WorkspaceDTO> getWorkspaces() { return workspaces; }
}
