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
package org.eclipse.mcp.ade.runtime;

/**
 * Indicates where a runtime binary comes from — both as a user-configured mode
 * and as the actual resolved source.
 */
public enum RuntimeSource {

    /**
     * Mode: try system first, fall back to embedded if not found.
     */
    AUTO,

    /**
     * Mode or active source: the system PATH.
     */
    SYSTEM,

    /**
     * Mode or active source: embedded runtime managed by MCP.
     */
    EMBEDDED,

    /**
     * Active source: not yet determined.
     */
    UNKNOWN;

    public static RuntimeSource fromValue(String value) {
        if (value == null) {
            return AUTO;
        }
        // Backward compatibility with old persisted values
        String upper = value.toUpperCase();
        if ("PATH".equals(upper)) {
            return SYSTEM;
        }
        if ("INSTALLER".equals(upper)) {
            return EMBEDDED;
        }
        try {
            return valueOf(upper);
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }
}
