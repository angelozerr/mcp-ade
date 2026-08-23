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

public class RuntimeStatusChangedWsMessage extends WsMessage {

    private final String runtimeId;
    private final String status;
    private final String error;

    public RuntimeStatusChangedWsMessage(String runtimeId, String status, String error) {
        super(WsMessageType.RUNTIME_STATUS_CHANGED);
        this.runtimeId = runtimeId;
        this.status = status;
        this.error = error;
    }

    public String getRuntimeId() { return runtimeId; }
    public String getStatus() { return status; }
    public String getError() { return error; }
}
