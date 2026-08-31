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
package org.eclipse.mcp.ade.extensions.jdtls.tools;

/**
 * Search scope for Java search operations.
 */
public enum SearchScope {

    PROJECT("project"),
    WORKSPACE("workspace");

    private final String value;

    SearchScope(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SearchScope fromString(String value) {
        if (PROJECT.value.equals(value)) {
            return PROJECT;
        }
        return WORKSPACE;
    }
}
