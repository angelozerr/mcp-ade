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
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.internal.corext.refactoring.code.InlineTempRefactoring;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.inlineVariable" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Inlines a local variable using the JDT LTK refactoring engine
 * ({@link InlineTempRefactoring}). Correctly handles:
 * <ul>
 *   <li>Side-effect analysis for the initializer expression</li>
 *   <li>Operator precedence when substituting expressions</li>
 *   <li>All references across the enclosing method scope</li>
 *   <li>Proper removal of the variable declaration</li>
 * </ul>
 * </p>
 */
public class InlineVariableHandler extends AbstractLTKRefactoringHandler {

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

        ASTNode node = NodeFinder.perform(ast, offset, 0);
        if (node == null) {
            throw new RuntimeException("No node found at position");
        }

        VariableDeclaration varDecl = null;
        if (node instanceof SimpleName) {
            ASTNode parent = node.getParent();
            if (parent instanceof VariableDeclaration) {
                varDecl = (VariableDeclaration) parent;
            }
        }
        if (varDecl == null) {
            // Try to resolve via codeSelect
            throw new RuntimeException("No local variable declaration found at position. Place cursor on the variable name in its declaration.");
        }

        InlineTempRefactoring refactoring = new InlineTempRefactoring(varDecl);

        return executeRefactoring(refactoring, params, monitor);
    }
}
