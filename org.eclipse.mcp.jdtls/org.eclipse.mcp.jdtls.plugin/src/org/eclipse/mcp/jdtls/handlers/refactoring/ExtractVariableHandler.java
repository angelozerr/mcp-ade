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
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractTempRefactoring;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.extractVariable" command.
 *
 * <p>Arguments: [{uri, startLine, startCharacter, endLine, endCharacter, variableName}]</p>
 *
 * <p>Extracts the selected expression into a local variable using the JDT LTK
 * refactoring engine ({@link ExtractTempRefactoring}). Correctly determines the
 * expression type, inserts the declaration before the enclosing statement, and
 * replaces all identical occurrences if applicable.</p>
 */
public class ExtractVariableHandler extends AbstractLTKRefactoringHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");
        String variableName = (String) params.get("variableName");

        if (uri == null || variableName == null || variableName.isEmpty()) {
            throw new RuntimeException("Missing required arguments: uri and variableName");
        }

        int startLine = ((Number) params.get("startLine")).intValue();
        int startCharacter = ((Number) params.get("startCharacter")).intValue();
        int endLine = ((Number) params.get("endLine")).intValue();
        int endCharacter = ((Number) params.get("endCharacter")).intValue();

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        String source = cu.getSource();
        int selStart = getOffset(source, startLine, startCharacter);
        int selEnd = getOffset(source, endLine, endCharacter);
        int selLength = selEnd - selStart;

        if (selLength <= 0) {
            throw new RuntimeException("Invalid selection range");
        }

        CompilationUnit ast = parseAST(cu, monitor);

        ASTNode covering = NodeFinder.perform(ast, selStart, selLength);
        if (covering == null) {
            throw new RuntimeException("No AST node found at the specified selection range. Check that the line/character positions are correct.");
        }
        if (covering instanceof Expression) {
            selStart = covering.getStartPosition();
            selLength = covering.getLength();
        }

        ExtractTempRefactoring refactoring = new ExtractTempRefactoring(ast, selStart, selLength);
        refactoring.setTempName(variableName);
        refactoring.setReplaceAllOccurrences(true);

        return executeRefactoring(refactoring, params, monitor);
    }
}
