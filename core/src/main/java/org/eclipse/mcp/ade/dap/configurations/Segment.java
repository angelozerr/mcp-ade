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
package org.eclipse.mcp.ade.dap.configurations;

/**
 * Base interface for pattern segments (static or dynamic).
 */
public interface Segment {
    /**
     * Check if this segment matches the input string.
     *
     * @param input the input string to match
     * @return the matched value, or null if no match
     */
    String matches(String input);

    /**
     * @return true if this is a dynamic segment (${...}), false for static text
     */
    boolean isDynamic();
}
