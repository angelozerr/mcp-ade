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
package org.eclipse.mcp.ade.extensions.jdtls.lsp;

/**
 * Constants for JDT.LS contribution fields.
 * Defines the fields that can be contributed to JDT.LS via contributes.jdtls.
 */
public final class JdtLsContributes {

    /**
     * Bundles field: JAR files to load as OSGi bundles in JDT.LS.
     * Example: "contributes": { "jdtls": { "bundles": ["plugins/*.jar"] } }
     */
    public static final String BUNDLES = "bundles";

    private JdtLsContributes() {
        // Utility class
    }
}
