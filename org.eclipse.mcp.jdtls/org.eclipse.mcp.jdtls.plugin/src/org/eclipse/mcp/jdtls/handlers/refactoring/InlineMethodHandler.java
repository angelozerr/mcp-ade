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
package org.eclipse.mcp.jdtls.handlers.refactoring;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.internal.corext.refactoring.code.InlineMethodRefactoring;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.inlineMethod" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Inlines a method using the JDT LTK refactoring engine
 * ({@link InlineMethodRefactoring}). Correctly handles:
 * <ul>
 *   <li>Multiple return paths and complex control flow</li>
 *   <li>Local variable name conflicts at call sites</li>
 *   <li>Argument expression side effects</li>
 *   <li>Multiple call sites across the workspace</li>
 *   <li>Method declaration removal after inlining</li>
 * </ul>
 * </p>
 */
public class InlineMethodHandler extends AbstractLTKRefactoringHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");

        if (uri == null) {
            throw new RuntimeException("Missing required argument: uri");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        int offset = JdtUtils.getOffset(cu, line, character);
        CompilationUnit ast = parseAST(cu, monitor);

        ASTNode selectedNode = NodeFinder.perform(ast, offset, 0);
        if (selectedNode == null) {
            throw new RuntimeException("No node found at position");
        }

        InlineMethodRefactoring refactoring = InlineMethodRefactoring.create(
                cu, ast, offset, 0);

        if (refactoring == null) {
            throw new RuntimeException("Cannot create inline method refactoring at this position. Place cursor on a method name.");
        }

        return executeRefactoring(refactoring, params, monitor);
    }
}
