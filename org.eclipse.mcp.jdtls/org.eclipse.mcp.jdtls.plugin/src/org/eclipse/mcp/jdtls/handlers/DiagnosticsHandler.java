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
package org.eclipse.mcp.jdtls.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.diagnostics" command.
 *
 * <p>Arguments: [{uris: ["file:///..."]}]</p>
 *
 * <p>Computes diagnostics (compilation errors and warnings) directly via JDT's
 * reconcile/AST APIs, avoiding the didOpen/publishDiagnostics cycle.</p>
 *
 * <p>Returns a list of PublishDiagnosticsParams-like objects with uri and diagnostics,
 * following the same format as MicroProfile's microprofile/java/diagnostics.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetDiagnosticsTool.java">javalens-mcp GetDiagnosticsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class DiagnosticsHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        List<String> uris = (List<String>) params.get("uris");
        if (uris == null || uris.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (String uri : uris) {
            if (monitor != null && monitor.isCanceled()) {
                break;
            }
            Map<String, Object> publishDiagnostics = computeDiagnostics(uri, monitor);
            if (publishDiagnostics != null) {
                results.add(publishDiagnostics);
            }
        }
        return results;
    }

    private Map<String, Object> computeDiagnostics(String uri, IProgressMonitor monitor) throws JavaModelException {
        ICompilationUnit cu = JdtUtils.getCompilationUnit(uri);
        if (cu == null) {
            return null;
        }

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        IProblem[] problems = ast.getProblems();
        List<Map<String, Object>> diagnostics = new ArrayList<>();

        for (IProblem problem : problems) {
            Map<String, Object> diagnostic = new HashMap<>();

            int startOffset = problem.getSourceStart();
            int endOffset = problem.getSourceEnd();

            // Use CompilationUnit to convert absolute offsets to line/column
            // getLineNumber returns 1-based, LSP expects 0-based
            // getColumnNumber returns 0-based (matches LSP)
            Map<String, Object> range = new HashMap<>();
            Map<String, Object> start = new HashMap<>();
            start.put("line", ast.getLineNumber(startOffset) - 1);
            start.put("character", ast.getColumnNumber(startOffset));
            Map<String, Object> end = new HashMap<>();
            end.put("line", ast.getLineNumber(endOffset) - 1);
            end.put("character", ast.getColumnNumber(endOffset) + 1);
            range.put("start", start);
            range.put("end", end);

            diagnostic.put("range", range);
            diagnostic.put("message", problem.getMessage());
            diagnostic.put("severity", getSeverity(problem));
            diagnostic.put("source", "jdt");
            diagnostic.put("code", problem.getID());

            diagnostics.add(diagnostic);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("diagnostics", diagnostics);
        return result;
    }

    private static int getSeverity(IProblem problem) {
        if (problem.isError()) {
            return 1; // DiagnosticSeverity.Error
        }
        if (problem.isWarning()) {
            return 2; // DiagnosticSeverity.Warning
        }
        return 3; // DiagnosticSeverity.Information
    }
}
