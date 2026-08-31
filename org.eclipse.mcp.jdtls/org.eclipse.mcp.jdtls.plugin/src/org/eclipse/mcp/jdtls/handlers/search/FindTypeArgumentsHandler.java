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
package org.eclipse.mcp.jdtls.handlers.search;

import org.eclipse.jdt.core.search.IJavaSearchConstants;

/**
 * Handler for "mcp.jdtls.findTypeArguments" command.
 *
 * <p>Arguments: [{fullyQualifiedName}] or [{uri, line, character}]</p>
 *
 * <p>Finds all usages of the given type as a generic type argument (e.g.
 * {@code List<Type>}) using fine-grained TYPE_ARGUMENT_TYPE_REFERENCE search.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindTypeArgumentsTool.java">javalens-mcp FindTypeArgumentsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindTypeArgumentsHandler extends AbstractFineGrainReferenceHandler {

    public FindTypeArgumentsHandler() {
        super(IJavaSearchConstants.TYPE_ARGUMENT_TYPE_REFERENCE, "type", "typeArguments");
    }
}
