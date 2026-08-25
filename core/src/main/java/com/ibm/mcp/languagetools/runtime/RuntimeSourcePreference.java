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
package com.ibm.mcp.languagetools.runtime;

/**
 * User preference for where to find a runtime binary.
 */
public enum RuntimeSourcePreference {

    /**
     * Try system PATH first, fall back to installer if not found.
     */
    AUTO,

    /**
     * Prefer system PATH. If not found, fall back to installer with a notification.
     */
    PATH,

    /**
     * Use only the runtime installed by MCP.
     */
    INSTALLER;

    public static RuntimeSourcePreference fromValue(String value) {
        if (value == null) {
            return AUTO;
        }
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AUTO;
        }
    }
}
