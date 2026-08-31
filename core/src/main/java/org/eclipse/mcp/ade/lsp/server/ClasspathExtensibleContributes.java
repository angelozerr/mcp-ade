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
package org.eclipse.mcp.ade.lsp.server;

/**
 * Constants for classpath-extensible server contribution fields.
 * Defines the fields that can be contributed to servers supporting classpath extensions.
 */
public final class ClasspathExtensibleContributes {

    /**
     * Classpath field: JAR files to add to the server's classpath.
     * Example: "contributes": { "microprofile": { "classpath": ["lib/*.jar"] } }
     */
    public static final String CLASSPATH = "classpath";

    private ClasspathExtensibleContributes() {
        // Utility class
    }
}
