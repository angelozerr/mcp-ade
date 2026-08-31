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

public class RuntimeStatusChangedWsMessage extends WsMessage {

    private final String runtimeId;
    private final String status;
    private final String error;
    private final String resolvedPath;
    private final String activeSource;
    private final boolean fallbackUsed;
    private final String sourcePreference;

    public RuntimeStatusChangedWsMessage(String runtimeId, String status, String error,
                                          String resolvedPath, String activeSource,
                                          boolean fallbackUsed, String sourcePreference) {
        super(WsMessageType.RUNTIME_STATUS_CHANGED);
        this.runtimeId = runtimeId;
        this.status = status;
        this.error = error;
        this.resolvedPath = resolvedPath;
        this.activeSource = activeSource;
        this.fallbackUsed = fallbackUsed;
        this.sourcePreference = sourcePreference;
    }

    public String getRuntimeId() { return runtimeId; }
    public String getStatus() { return status; }
    public String getError() { return error; }
    public String getResolvedPath() { return resolvedPath; }
    public String getActiveSource() { return activeSource; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public String getSourcePreference() { return sourcePreference; }
}
