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
package org.eclipse.mcp.ade.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Tracks the currently connected MCP client(s).
 */
@ApplicationScoped
public class McpClientTracker {

    private static final Logger LOG = Logger.getLogger(McpClientTracker.class);

    private volatile String currentClientName = "No client connected";
    private volatile String currentConnectionId = null;

    void onClientConnected(@Observes McpClientConnectedEvent event) {
        this.currentClientName = event.getClientName();
        this.currentConnectionId = event.getConnectionId();
        LOG.infof("Updated current MCP client: %s [%s]", currentClientName, currentConnectionId);
    }

    public String getCurrentClientName() {
        return currentClientName;
    }

    public String getCurrentConnectionId() {
        return currentConnectionId;
    }
}
