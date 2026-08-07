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
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.extractMethod" command.
 *
 * <p>Arguments: [{uri, startLine, startCharacter, endLine, endCharacter, methodName}]</p>
 *
 * <p>Extracts the selected statements into a new private method using the JDT LTK
 * refactoring engine ({@link ExtractMethodRefactoring}). Correctly handles:
 * <ul>
 *   <li>Parameter detection (variables defined before selection, used inside)</li>
 *   <li>Return value analysis (single and multiple return values)</li>
 *   <li>Control flow statements (break, continue, return) spanning selection boundary</li>
 *   <li>Exception handling and throws clause generation</li>
 *   <li>Static context detection</li>
 * </ul>
 * </p>
 */
public class ExtractMethodHandler extends AbstractLTKRefactoringHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");
        String methodName = (String) params.get("methodName");

        if (uri == null || methodName == null || methodName.isEmpty()) {
            throw new RuntimeException("Missing required arguments: uri and methodName");
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

        ExtractMethodRefactoring refactoring = new ExtractMethodRefactoring(ast, selStart, selLength);
        refactoring.setMethodName(methodName);
        refactoring.setVisibility(org.eclipse.jdt.core.dom.Modifier.PRIVATE);

        return executeRefactoring(refactoring, params, monitor);
    }
}
