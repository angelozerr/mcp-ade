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
package org.eclipse.mcp.ade.mcp.trace;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.RawMessage;

/**
 * No-op MCP trace collector that discards all traces.
 */
public class NoOpMcpTraceCollector extends McpTraceCollector {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void addTrace(String workspaceUri, String contextId, String content, MessageType type) {
    }

    @Override
    public void addTrace(McpTraceDirection direction, RawMessage message, McpConnection connection) {
    }
}
