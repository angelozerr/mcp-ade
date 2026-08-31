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
package org.eclipse.mcp.jdtls.handlers.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.analyzeFile" command.
 *
 * <p>Arguments: [{uri}]</p>
 *
 * <p>Comprehensive file analysis combining types, methods, fields, diagnostics,
 * imports, unused imports, LOC, and per-method complexity.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/AnalyzeFileTool.java">javalens-mcp AnalyzeFileTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class AnalyzeFileHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        // Count types, methods, fields
        int[] typeCount = {0};
        int[] fieldCount = {0};
        int[] importCount = {0};
        List<Map<String, Object>> methods = new ArrayList<>();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                typeCount[0]++;
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                fieldCount[0] += node.fragments().size();
                return true;
            }

            @Override
            public boolean visit(ImportDeclaration node) {
                importCount[0]++;
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                Map<String, Object> methodInfo = new HashMap<>();
                methodInfo.put("name", node.getName().getIdentifier());
                methodInfo.put("line", ast.getLineNumber(node.getStartPosition()));

                int startLine = ast.getLineNumber(node.getStartPosition());
                int endLine = ast.getLineNumber(node.getStartPosition() + node.getLength());
                int loc = endLine - startLine + 1;
                methodInfo.put("loc", loc);

                // Compute cyclomatic complexity
                int[] complexity = {1};
                node.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(IfStatement n) { complexity[0]++; return true; }
                    @Override
                    public boolean visit(ForStatement n) { complexity[0]++; return true; }
                    @Override
                    public boolean visit(EnhancedForStatement n) { complexity[0]++; return true; }
                    @Override
                    public boolean visit(WhileStatement n) { complexity[0]++; return true; }
                    @Override
                    public boolean visit(DoStatement n) { complexity[0]++; return true; }
                    @Override
                    public boolean visit(SwitchCase n) {
                        if (!n.isDefault()) complexity[0]++;
                        return true;
                    }
                    @Override
                    public boolean visit(CatchClause n) { complexity[0]++; return true; }
                    @Override
                    public boolean visit(ConditionalExpression n) { complexity[0]++; return true; }
                });

                methodInfo.put("cyclomaticComplexity", complexity[0]);
                methods.add(methodInfo);
                return false;
            }
        });

        // Count diagnostics
        IProblem[] problems = ast.getProblems();
        int errors = 0;
        int warnings = 0;
        int unusedImports = 0;

        for (IProblem problem : problems) {
            if (problem.isError()) {
                errors++;
            } else if (problem.isWarning()) {
                warnings++;
            }
            // Check for unused import problems
            int id = problem.getID();
            if (id == IProblem.UnusedImport
                    || id == IProblem.DuplicateImport
                    || id == IProblem.ImportNotFound) {
                unusedImports++;
            }
        }

        // Compute total LOC
        String source = cu.getSource();
        int loc = 0;
        if (source != null) {
            loc = source.split("\\r?\\n", -1).length;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("types", typeCount[0]);
        result.put("methods", methods);
        result.put("fields", fieldCount[0]);
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("loc", loc);
        result.put("imports", importCount[0]);
        result.put("unusedImports", unusedImports);
        return result;
    }
}
