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
package com.ibm.mcp.jdtls.handlers.refactoring;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.refactoring.structure.MoveInnerToTopRefactoring;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.moveTypeToNewFile" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Moves a nested/inner type to a new file using the JDT LTK refactoring engine
 * ({@link MoveInnerToTopRefactoring}). Correctly handles:
 * <ul>
 *   <li>Creating a new top-level type file with proper imports</li>
 *   <li>Removing the type from the original file</li>
 *   <li>Handling enclosing instance references</li>
 *   <li>Updating all type references across the workspace</li>
 *   <li>Static keyword removal for top-level conversion</li>
 * </ul>
 * </p>
 */
public class MoveTypeToNewFileHandler extends AbstractLTKRefactoringHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");

        if (uri == null) {
            throw new RuntimeException("Missing required argument: uri");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        IType type = JdtUtils.resolveTypeAtPosition(uri, line, character, monitor);
        if (type == null) {
            throw new RuntimeException("No type found at position");
        }

        IType declaringType = type.getDeclaringType();
        if (declaringType == null) {
            throw new RuntimeException("Type is already a top-level type - cannot move to new file");
        }

        MoveInnerToTopRefactoring refactoring = new MoveInnerToTopRefactoring(type, null);

        return executeRefactoring(refactoring, params, monitor);
    }
}
