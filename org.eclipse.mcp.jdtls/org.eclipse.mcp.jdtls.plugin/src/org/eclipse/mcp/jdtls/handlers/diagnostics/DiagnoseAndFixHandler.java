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
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;
import org.eclipse.text.edits.TextEdit;

/**
 * Handler for "mcp.jdtls.diagnoseAndFix" command.
 *
 * <p>Arguments: [{uri, autoFix (optional boolean, default false)}]</p>
 *
 * <p>Composed handler that runs diagnostics, determines available quick fixes,
 * and optionally applies safe fixes automatically.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/DiagnoseAndFixTool.java">javalens-mcp DiagnoseAndFixTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class DiagnoseAndFixHandler implements ICommandHandler {

    /** Fix IDs considered safe for automatic application. */
    private static final Set<String> SAFE_FIX_IDS = Set.of(
            "remove_import",
            "add_override"
    );

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");
        boolean autoFix = Boolean.TRUE.equals(params.get("autoFix"));

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        // Step 1: Run diagnostics
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        IProblem[] problems = ast.getProblems();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        int fixesApplied = 0;

        // Step 2: For each problem, determine available fixes
        for (IProblem problem : problems) {
            if (monitor != null && monitor.isCanceled()) {
                break;
            }

            Map<String, Object> diagInfo = new HashMap<>();
            int problemLine = ast.getLineNumber(problem.getSourceStart()) - 1;
            diagInfo.put("message", problem.getMessage());
            diagInfo.put("line", problemLine);
            diagInfo.put("severity", problem.isError() ? "error" : "warning");

            List<String> fixes = determineFixes(problem);
            diagInfo.put("fixes", fixes);

            diagnostics.add(diagInfo);
        }

        // Step 3: If autoFix, apply safe fixes
        if (autoFix && !diagnostics.isEmpty()) {
            fixesApplied = applySafeFixes(cu, ast, problems, monitor);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("diagnostics", diagnostics);
        result.put("totalDiagnostics", diagnostics.size());
        if (autoFix) {
            result.put("fixesApplied", fixesApplied);
        }
        return result;
    }

    private List<String> determineFixes(IProblem problem) {
        List<String> fixes = new ArrayList<>();
        int id = problem.getID();

        switch (id) {
            case IProblem.UndefinedType:
            case IProblem.UndefinedName:
                fixes.add("add_import");
                break;
            case IProblem.UnhandledException:
                fixes.add("add_throws");
                fixes.add("surround_try_catch");
                break;
            case IProblem.UnusedImport:
                fixes.add("remove_import");
                break;
            case IProblem.MissingOverrideAnnotation:
                fixes.add("add_override");
                break;
            case IProblem.UndefinedMethod:
                fixes.add("create_method");
                break;
            default:
                break;
        }

        return fixes;
    }

    private int applySafeFixes(ICompilationUnit cu, CompilationUnit ast, IProblem[] problems,
            IProgressMonitor monitor) throws Exception {
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        int count = 0;

        for (IProblem problem : problems) {
            int id = problem.getID();

            if (id == IProblem.UnusedImport) {
                // Remove unused import
                ASTNode node = NodeFinder.perform(ast, problem.getSourceStart(),
                        problem.getSourceEnd() - problem.getSourceStart());
                if (node != null) {
                    ASTNode parent = node;
                    while (parent != null && !(parent instanceof ImportDeclaration)) {
                        parent = parent.getParent();
                    }
                    if (parent instanceof ImportDeclaration) {
                        rewrite.remove(parent, null);
                        count++;
                    }
                }
            } else if (id == IProblem.MissingOverrideAnnotation) {
                // Add @Override annotation
                ASTNode node = NodeFinder.perform(ast, problem.getSourceStart(),
                        problem.getSourceEnd() - problem.getSourceStart());
                if (node != null) {
                    ASTNode parent = node;
                    while (parent != null && !(parent instanceof MethodDeclaration)) {
                        parent = parent.getParent();
                    }
                    if (parent instanceof MethodDeclaration) {
                        MethodDeclaration method = (MethodDeclaration) parent;
                        MarkerAnnotation annotation = ast.getAST().newMarkerAnnotation();
                        annotation.setTypeName(ast.getAST().newSimpleName("Override"));
                        ListRewrite modifiers = rewrite.getListRewrite(method,
                                MethodDeclaration.MODIFIERS2_PROPERTY);
                        modifiers.insertFirst(annotation, null);
                        count++;
                    }
                }
            }
        }

        if (count > 0) {
            TextEdit edits = rewrite.rewriteAST();
            cu.applyTextEdit(edits, monitor);
            cu.save(monitor, true);
        }

        return count;
    }
}
