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

public class ServerStatusChangedWsMessage extends WsMessage {

    private final String workspaceUri;
    private final String serverId;
    private final String serverType;
    private final String oldStatus;
    private final String newStatus;
    private final String statusMessage;
    private final Double installProgress;
    private final Boolean isReady;

    public ServerStatusChangedWsMessage(String workspaceUri, String serverId,
                                        String serverType,
                                        String oldStatus, String newStatus,
                                        String statusMessage, Double installProgress,
                                        Boolean isReady) {
        super(WsMessageType.SERVER_STATUS_CHANGED);
        this.workspaceUri = workspaceUri;
        this.serverId = serverId;
        this.serverType = serverType;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.statusMessage = statusMessage;
        this.installProgress = installProgress;
        this.isReady = isReady;
    }

    public String getWorkspaceUri() { return workspaceUri; }
    public String getServerId() { return serverId; }
    public String getServerType() { return serverType; }
    public String getOldStatus() { return oldStatus; }
    public String getNewStatus() { return newStatus; }
    public String getStatusMessage() { return statusMessage; }
    public Double getInstallProgress() { return installProgress; }
    public Boolean getIsReady() { return isReady; }
}
