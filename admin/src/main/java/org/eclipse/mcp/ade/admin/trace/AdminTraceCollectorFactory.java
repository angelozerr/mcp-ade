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
package org.eclipse.mcp.ade.admin.trace;

import org.eclipse.mcp.ade.mcp.trace.McpTraceCollector;
import org.eclipse.mcp.ade.trace.DefaultTraceCollector;
import org.eclipse.mcp.ade.trace.TraceCollector;
import org.eclipse.mcp.ade.trace.TraceCollectorFactory;
import org.eclipse.mcp.ade.trace.TraceKind;

/**
 * Factory that creates real trace collectors when the admin module is present.
 * Registered via META-INF/services.
 */
public class AdminTraceCollectorFactory implements TraceCollectorFactory {

    @Override
    public TraceCollector createTraceCollector(TraceKind kind) {
        return new DefaultTraceCollector(kind);
    }

    @Override
    public McpTraceCollector createMcpTraceCollector() {
        return new McpTraceCollector();
    }
}
