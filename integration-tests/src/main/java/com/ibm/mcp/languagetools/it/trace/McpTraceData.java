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
package com.ibm.mcp.languagetools.it.trace;

import java.util.Map;

/**
 * Parsed MCP trace data containing the tool invocation request and expected response.
 *
 * @param toolName           the MCP tool name (e.g., "get_hover_info", "find_references")
 * @param arguments          the tool arguments map
 * @param expectedResultText the expected text content from the first content entry of the response
 *                           (null if no text content expected)
 * @param expectedIsError    whether the expected response is an error
 */
public record McpTraceData(
        String toolName,
        Map<String, Object> arguments,
        String expectedResultText,
        boolean expectedIsError
) {
}
