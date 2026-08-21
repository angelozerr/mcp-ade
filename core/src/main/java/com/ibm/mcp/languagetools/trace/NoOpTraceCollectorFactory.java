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
package com.ibm.mcp.languagetools.trace;

import com.ibm.mcp.languagetools.mcp.trace.McpTraceCollector;
import com.ibm.mcp.languagetools.mcp.trace.NoOpMcpTraceCollector;

/**
 * Default factory that creates no-op trace collectors.
 * Used when no admin module is on the classpath.
 */
public class NoOpTraceCollectorFactory implements TraceCollectorFactory {

    @Override
    public TraceCollector createTraceCollector(TraceKind kind) {
        return NoOpTraceCollector.INSTANCE;
    }

    @Override
    public McpTraceCollector createMcpTraceCollector() {
        return new NoOpMcpTraceCollector();
    }
}
