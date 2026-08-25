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

public class InstallStatusChangedWsMessage extends WsMessage {

    private final String serverId;
    private final String installationStatus;

    public InstallStatusChangedWsMessage(String serverId, String installationStatus) {
        super(WsMessageType.INSTALL_STATUS_CHANGED);
        this.serverId = serverId;
        this.installationStatus = installationStatus;
    }

    public String getServerId() { return serverId; }
    public String getInstallationStatus() { return installationStatus; }
}
