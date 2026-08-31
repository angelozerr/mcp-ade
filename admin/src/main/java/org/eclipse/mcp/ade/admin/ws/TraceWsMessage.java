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

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.mcp.ade.trace.TraceCollector;

public abstract class TraceWsMessage extends WsMessage {

    private final String content;
    private final TraceCollector.MessageType messageType;

    protected TraceWsMessage(WsMessageType type, String content, TraceCollector.MessageType messageType) {
        super(type);
        this.content = content;
        this.messageType = messageType == TraceCollector.MessageType.TRACE ? null : messageType;
    }

    public String getContent() {
        return content;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public TraceCollector.MessageType getMessageType() {
        return messageType;
    }
}
