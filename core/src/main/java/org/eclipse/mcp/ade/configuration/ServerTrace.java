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
package org.eclipse.mcp.ade.configuration;

/**
 * Server trace level (like VS Code trace setting).
 * Controls verbosity of server communication logging.
 *
 * Values are stored in lowercase for LSP/DAP/MCP protocol compatibility.
 */
public enum ServerTrace {
    /**
     * No tracing.
     */
    off,

    /**
     * Trace messages only.
     */
    messages,

    /**
     * Verbose tracing (messages + detailed info).
     */
    verbose;

    /**
     * Parse from string value (case-insensitive).
     */
    public static ServerTrace fromValue(String value) {
        if (value == null) {
            return off;
        }
        try {
            return valueOf(value.toLowerCase());
        } catch (IllegalArgumentException e) {
            return off;
        }
    }

    @Override
    public String toString() {
        return name(); // Already lowercase
    }
}
