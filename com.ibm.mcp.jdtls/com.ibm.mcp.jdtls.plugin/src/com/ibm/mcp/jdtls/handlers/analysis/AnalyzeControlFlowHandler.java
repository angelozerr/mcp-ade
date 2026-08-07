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
package com.ibm.mcp.jdtls.handlers.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
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
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.WhileStatement;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.analyzeControlFlow" command.
 *
 * <p>Arguments: [{uri, line, character}] to resolve a method.</p>
 *
 * <p>Uses ASTParser and ASTVisitor on the MethodDeclaration body to track
 * if/else branches, loops, try/catch/finally, switch/case, return/throw statements.
 * Builds a simplified control flow summary.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/AnalyzeControlFlowTool.java">javalens-mcp AnalyzeControlFlowTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class AnalyzeControlFlowHandler implements ICommandHandler {

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

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);

        IMethod method = null;
        for (IJavaElement element : elements) {
            if (element instanceof IMethod) {
                method = (IMethod) element;
                break;
            }
        }

        if (method == null) {
            throw new RuntimeException("No method found at position");
        }

        // Parse the compilation unit AST
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        // Find the MethodDeclaration at the given offset
        final IMethod targetMethod = method;
        final MethodDeclaration[] foundMethod = {null};

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getName().getIdentifier().equals(targetMethod.getElementName())) {
                    int methodStart = node.getStartPosition();
                    int methodEnd = methodStart + node.getLength();
                    if (offset >= methodStart && offset <= methodEnd) {
                        foundMethod[0] = node;
                    }
                }
                return false;
            }
        });

        if (foundMethod[0] == null) {
            throw new RuntimeException("Method declaration not found in AST");
        }

        MethodDeclaration methodDecl = foundMethod[0];

        // Analyze control flow
        List<Map<String, Object>> branches = new ArrayList<>();
        List<Map<String, Object>> loops = new ArrayList<>();
        List<Map<String, Object>> exceptionHandlers = new ArrayList<>();
        List<Map<String, Object>> returnPoints = new ArrayList<>();
        int[] pathCount = {1};

        methodDecl.accept(new ASTVisitor() {
            @Override
            public boolean visit(IfStatement node) {
                pathCount[0]++;
                Map<String, Object> branch = new HashMap<>();
                branch.put("type", "if");
                branch.put("line", ast.getLineNumber(node.getStartPosition()));
                branch.put("condition", node.getExpression().toString());
                branch.put("hasElse", node.getElseStatement() != null);
                branches.add(branch);
                return true;
            }

            @Override
            public boolean visit(ConditionalExpression node) {
                pathCount[0]++;
                Map<String, Object> branch = new HashMap<>();
                branch.put("type", "ternary");
                branch.put("line", ast.getLineNumber(node.getStartPosition()));
                branch.put("condition", node.getExpression().toString());
                branches.add(branch);
                return true;
            }

            @Override
            public boolean visit(SwitchCase node) {
                if (!node.isDefault()) {
                    pathCount[0]++;
                    Map<String, Object> branch = new HashMap<>();
                    branch.put("type", "case");
                    branch.put("line", ast.getLineNumber(node.getStartPosition()));
                    branch.put("condition", node.toString().trim());
                    branches.add(branch);
                }
                return true;
            }

            @Override
            public boolean visit(ForStatement node) {
                Map<String, Object> loop = new HashMap<>();
                loop.put("type", "for");
                loop.put("line", ast.getLineNumber(node.getStartPosition()));
                if (node.getExpression() != null) {
                    loop.put("condition", node.getExpression().toString());
                }
                loops.add(loop);
                return true;
            }

            @Override
            public boolean visit(EnhancedForStatement node) {
                Map<String, Object> loop = new HashMap<>();
                loop.put("type", "foreach");
                loop.put("line", ast.getLineNumber(node.getStartPosition()));
                loop.put("condition", node.getParameter().getName() + " : " + node.getExpression());
                loops.add(loop);
                return true;
            }

            @Override
            public boolean visit(WhileStatement node) {
                Map<String, Object> loop = new HashMap<>();
                loop.put("type", "while");
                loop.put("line", ast.getLineNumber(node.getStartPosition()));
                loop.put("condition", node.getExpression().toString());
                loops.add(loop);
                return true;
            }

            @Override
            public boolean visit(DoStatement node) {
                Map<String, Object> loop = new HashMap<>();
                loop.put("type", "do-while");
                loop.put("line", ast.getLineNumber(node.getStartPosition()));
                loop.put("condition", node.getExpression().toString());
                loops.add(loop);
                return true;
            }

            @Override
            public boolean visit(TryStatement node) {
                Map<String, Object> handler = new HashMap<>();
                handler.put("type", "try");
                handler.put("line", ast.getLineNumber(node.getStartPosition()));
                handler.put("catchCount", node.catchClauses().size());
                handler.put("hasFinally", node.getFinally() != null);
                exceptionHandlers.add(handler);
                return true;
            }

            @Override
            public boolean visit(CatchClause node) {
                pathCount[0]++;
                Map<String, Object> handler = new HashMap<>();
                handler.put("type", "catch");
                handler.put("line", ast.getLineNumber(node.getStartPosition()));
                handler.put("exceptionType", node.getException().getType().toString());
                exceptionHandlers.add(handler);
                return true;
            }

            @Override
            public boolean visit(ReturnStatement node) {
                Map<String, Object> ret = new HashMap<>();
                ret.put("type", "return");
                ret.put("line", ast.getLineNumber(node.getStartPosition()));
                if (node.getExpression() != null) {
                    ret.put("expression", node.getExpression().toString());
                }
                returnPoints.add(ret);
                return true;
            }

            @Override
            public boolean visit(ThrowStatement node) {
                Map<String, Object> ret = new HashMap<>();
                ret.put("type", "throw");
                ret.put("line", ast.getLineNumber(node.getStartPosition()));
                ret.put("expression", node.getExpression().toString());
                returnPoints.add(ret);
                return true;
            }
        });

        Map<String, Object> result = new HashMap<>();
        result.put("method", method.getElementName());
        result.put("paths", pathCount[0]);
        result.put("branches", branches);
        result.put("loops", loops);
        result.put("exceptionHandlers", exceptionHandlers);
        result.put("returnPoints", returnPoints);
        return result;
    }
}
