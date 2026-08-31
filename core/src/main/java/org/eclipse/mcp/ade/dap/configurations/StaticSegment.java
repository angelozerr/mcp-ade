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
 * A static segment of literal text in the pattern.
 */
public class StaticSegment implements Segment {
    private final String value;

    public StaticSegment(String value) {
        this.value = value;
    }

    @Override
    public String matches(String input) {
        if (input.startsWith(value)) {
            return value;
        }
        return null;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    public String getValue() {
        return value;
    }
}
