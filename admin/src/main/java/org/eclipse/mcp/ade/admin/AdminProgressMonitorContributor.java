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
package org.eclipse.mcp.ade.admin;

import org.eclipse.mcp.ade.progress.ProgressBroadcaster;
import org.eclipse.mcp.ade.progress.ProgressContext;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.progress.ProgressMonitorContributor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Admin module's progress monitor contributor.
 * Adds TraceProgressMonitor for WebSocket broadcasting to Admin UI.
 */
@ApplicationScoped
public class AdminProgressMonitorContributor implements ProgressMonitorContributor {

    private static final Logger LOG = Logger.getLogger(AdminProgressMonitorContributor.class);

    @Inject
    ProgressBroadcaster broadcaster;

    @Override
    public ProgressMonitor createMonitor(ProgressContext context) {
        String taskId = context.getTaskId();
        String serverId = context.getServerId();
        String title = context.getTitle();

        LOG.infof("Creating WebSocketProgressMonitor for task '%s' (title=%s)", taskId, title);

        return new WebSocketProgressMonitor(
            broadcaster,
            taskId,
            serverId,  // Can be null for global operations
            title
        );
    }

    @Override
    public int getPriority() {
        // Lower priority than MCP monitor (which is 0)
        return -10;
    }
}
