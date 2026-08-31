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

import org.eclipse.mcp.ade.trace.TraceCollector;

public class RuntimeTraceWsMessage extends TraceWsMessage {

    private final String runtimeId;

    public RuntimeTraceWsMessage(String runtimeId, String content, TraceCollector.MessageType messageType) {
        super(WsMessageType.RUNTIME_TRACE, content, messageType);
        this.runtimeId = runtimeId;
    }

    public String getRuntimeId() {
        return runtimeId;
    }
}
