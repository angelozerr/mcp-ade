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
package org.eclipse.mcp.ade.dap.server;

/**
 * Debug server wait strategy.
 */
public enum DebugServerWaitStrategy {

    /** Waits for a fixed timeout before assuming the server is ready. */
    TIMEOUT,

    /** Waits for a specific trace/log message indicating server readiness. */
    TRACE;

    /**
     * Retrieves the corresponding DebugServerWaitStrategy from a string value.
     *
     * @param value the string representation of the strategy (case-insensitive).
     * @return the matching DebugServerWaitStrategy, or TIMEOUT if the input is invalid.
     */
    public static DebugServerWaitStrategy get(String value) {
        try {
            return DebugServerWaitStrategy.valueOf(value.toUpperCase());
        } catch (Exception e) {
            return DebugServerWaitStrategy.TIMEOUT;
        }
    }
}
