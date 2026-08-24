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

public class FileWatcherStatusChangedWsMessage extends WsMessage {

    private final String workspaceUri;
    private final String status;
    private final String failureReason;
    private final int scannedDirs;

    public FileWatcherStatusChangedWsMessage(String workspaceUri, String status, String failureReason, int scannedDirs) {
        super(WsMessageType.FILE_WATCHER_STATUS_CHANGED);
        this.workspaceUri = workspaceUri;
        this.status = status;
        this.failureReason = failureReason;
        this.scannedDirs = scannedDirs;
    }

    public String getWorkspaceUri() { return workspaceUri; }
    public String getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public int getScannedDirs() { return scannedDirs; }
}
