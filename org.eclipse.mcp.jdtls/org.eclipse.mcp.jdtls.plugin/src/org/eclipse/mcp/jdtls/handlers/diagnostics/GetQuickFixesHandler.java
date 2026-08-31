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
package org.eclipse.mcp.jdtls.handlers.diagnostics;

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
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getQuickFixes" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Gets problems at or near the given line and suggests available quick fixes
 * based on the problem ID.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetQuickFixesTool.java">javalens-mcp GetQuickFixesTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetQuickFixesHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");
        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        // Reconcile to get current problems
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        IProblem[] problems = ast.getProblems();
        List<Map<String, Object>> problemResults = new ArrayList<>();
        int totalFixes = 0;

        for (IProblem problem : problems) {
            // Match problems at or near the given line (0-based)
            int problemLine = ast.getLineNumber(problem.getSourceStart()) - 1;
            if (Math.abs(problemLine - line) > 1) {
                continue;
            }

            Map<String, Object> problemInfo = new HashMap<>();
            problemInfo.put("message", problem.getMessage());
            problemInfo.put("severity", problem.isError() ? "error" : "warning");

            List<Map<String, String>> fixes = suggestFixes(problem);
            problemInfo.put("fixes", fixes);
            totalFixes += fixes.size();

            problemResults.add(problemInfo);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("line", line);
        result.put("problems", problemResults);
        result.put("totalFixes", totalFixes);
        return result;
    }

    private List<Map<String, String>> suggestFixes(IProblem problem) {
        List<Map<String, String>> fixes = new ArrayList<>();
        int id = problem.getID();

        switch (id) {
            case IProblem.UndefinedType:
            case IProblem.UndefinedName:
                fixes.add(createFix("add_import", "Add missing import"));
                break;
            case IProblem.UnhandledException:
                fixes.add(createFix("add_throws", "Add throws declaration"));
                fixes.add(createFix("surround_try_catch", "Surround with try/catch"));
                break;
            case IProblem.UnusedImport:
                fixes.add(createFix("remove_import", "Remove unused import"));
                break;
            case IProblem.MissingOverrideAnnotation:
                fixes.add(createFix("add_override", "Add @Override annotation"));
                break;
            case IProblem.UndefinedMethod:
                fixes.add(createFix("create_method", "Create method"));
                break;
            default:
                fixes.add(createFix("info", "Problem: " + problem.getMessage()));
                break;
        }

        return fixes;
    }

    private Map<String, String> createFix(String fixId, String description) {
        Map<String, String> fix = new HashMap<>();
        fix.put("fixId", fixId);
        fix.put("description", description);
        return fix;
    }
}
