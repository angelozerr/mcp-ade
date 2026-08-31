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
package org.eclipse.mcp.ade.progress;

import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class ProgressMonitorManager {

    @Inject
    Instance<ProgressMonitorContributor> progressContributors;

    /**
     * Create a progress monitor that broadcasts to both MCP client and admin contributors.
     */
    public ProgressMonitor createProgressMonitor(Progress progress, Cancellation cancellation, ProgressContext context) {
        McpProgressMonitor mcpMonitor = new McpProgressMonitor(progress, cancellation);

        List<ProgressMonitor> monitors = new ArrayList<>();
        monitors.add(mcpMonitor);

        for (ProgressMonitorContributor contributor : progressContributors) {
            ProgressMonitor contributed = contributor.createMonitor(context);
            if (contributed != null && contributed != ProgressMonitor.none()) {
                monitors.add(contributed);
            }
        }

        return monitors.size() > 1
                ? new MultiProgressMonitor(monitors.toArray(new ProgressMonitor[0]))
                : mcpMonitor;
    }
}
