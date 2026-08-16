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

/**
 * SPI factory for creating trace collectors.
 * <p>
 * When no implementation is found on the classpath (e.g. admin module not present),
 * a {@link NoOpTraceCollector} is used for LSP/DAP and a no-op
 * {@link McpTraceCollector} for MCP — avoiding memory accumulation.
 */
public interface TraceCollectorFactory {

    TraceCollector createLspTraceCollector();

    TraceCollector createDapTraceCollector();

    TraceCollector createBspTraceCollector();

    McpTraceCollector createMcpTraceCollector();
}
