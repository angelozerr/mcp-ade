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
package com.ibm.mcp.languagetools.admin.trace;

import com.ibm.mcp.languagetools.mcp.trace.McpTraceCollector;
import com.ibm.mcp.languagetools.trace.DefaultTraceCollector;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.trace.TraceCollectorFactory;
import com.ibm.mcp.languagetools.trace.TraceKind;

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
