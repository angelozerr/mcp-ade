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

import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.configuration.ApplicationConfiguration;
import org.eclipse.mcp.ade.configuration.ServerTrace;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.McpTrafficListener;
import io.quarkiverse.mcp.server.RawMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class McpTraceTrafficListener implements McpTrafficListener {

    @Inject
    Application application;

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @Override
    public void onMessageReceived(RawMessage message, McpConnection connection) {
        McpTraceCollector traceCollector = application.getMcpTraceCollector();
        if (traceCollector.isEnabled() && applicationConfiguration.getMcpTraceLevel() != ServerTrace.off) {
            traceCollector.addTrace(McpTraceDirection.RECEIVED, message, connection);
        }
    }

    @Override
    public void onMessageSent(RawMessage message, McpConnection connection) {
        McpTraceCollector traceCollector = application.getMcpTraceCollector();
        if (traceCollector.isEnabled() && applicationConfiguration.getMcpTraceLevel() != ServerTrace.off) {
            traceCollector.addTrace(McpTraceDirection.SENT, message, connection);
        }
    }
}
