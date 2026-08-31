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
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getComplexityMetrics" command.
 *
 * <p>Arguments: [{uri}]</p>
 *
 * <p>Computes cyclomatic complexity and LOC per method for a file.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetComplexityMetricsTool.java">javalens-mcp GetComplexityMetricsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetComplexityMetricsHandler implements ICommandHandler {

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
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        List<Map<String, Object>> methods = new ArrayList<>();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                Map<String, Object> methodInfo = new HashMap<>();
                methodInfo.put("name", node.getName().getIdentifier());
                methodInfo.put("line", ast.getLineNumber(node.getStartPosition()));

                int startLine = ast.getLineNumber(node.getStartPosition());
                int endLine = ast.getLineNumber(node.getStartPosition() + node.getLength());
                methodInfo.put("loc", endLine - startLine + 1);

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

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("methods", methods);
        result.put("totalMethods", methods.size());
        return result;
    }
}
