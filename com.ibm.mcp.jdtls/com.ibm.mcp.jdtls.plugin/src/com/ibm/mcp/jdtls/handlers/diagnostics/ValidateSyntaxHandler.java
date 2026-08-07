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
package com.ibm.mcp.jdtls.handlers.diagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.validateSyntax" command.
 *
 * <p>Arguments: [{uri}]</p>
 *
 * <p>Performs quick syntax-only validation using ASTParser with binding
 * resolution disabled. Filters results to syntax errors only.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/ValidateSyntaxTool.java">javalens-mcp ValidateSyntaxTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class ValidateSyntaxHandler implements ICommandHandler {

    // IProblem IDs in the syntax error range
    private static final int SYNTAX_RANGE_START = IProblem.Syntax;
    private static final int SYNTAX_RANGE_END = IProblem.Syntax + 100;

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        // Parse with no binding resolution for fast syntax-only check
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(false);
        parser.setStatementsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        IProblem[] problems = ast.getProblems();
        List<Map<String, Object>> syntaxErrors = new ArrayList<>();

        for (IProblem problem : problems) {
            if (!isSyntaxError(problem)) {
                continue;
            }

            Map<String, Object> errorInfo = new HashMap<>();
            errorInfo.put("message", problem.getMessage());
            errorInfo.put("line", ast.getLineNumber(problem.getSourceStart()) - 1);
            errorInfo.put("character", ast.getColumnNumber(problem.getSourceStart()));
            errorInfo.put("severity", problem.isError() ? "error" : "warning");
            syntaxErrors.add(errorInfo);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("syntaxErrors", syntaxErrors);
        result.put("valid", syntaxErrors.isEmpty());
        result.put("count", syntaxErrors.size());
        return result;
    }

    private boolean isSyntaxError(IProblem problem) {
        if (!problem.isError()) {
            return false;
        }
        int id = problem.getID();
        return id >= SYNTAX_RANGE_START && id < SYNTAX_RANGE_END;
    }
}
