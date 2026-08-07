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
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.convertAnonymousToLambda" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Converts an anonymous class to a lambda expression when the anonymous class
 * implements a functional interface (single abstract method). Handles:
 * <ul>
 *   <li>Single-expression lambdas (return statement → expression form)</li>
 *   <li>Multi-statement block lambdas</li>
 *   <li>Single-parameter parenthesis elision</li>
 * </ul>
 * </p>
 *
 * <p>Note: No LTK refactoring exists for this conversion. Uses AST-based approach.</p>
 */
public class ConvertAnonymousToLambdaHandler extends AbstractRefactoringHandler {

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

        String source = cu.getSource();
        int offset = JdtUtils.getOffset(cu, line, character);
        CompilationUnit ast = parseAST(cu, monitor);

        ClassInstanceCreation creation = findClassInstanceCreation(ast, offset);
        if (creation == null) {
            throw new RuntimeException("No anonymous class instance creation found at position");
        }

        AnonymousClassDeclaration anonymousDecl = creation.getAnonymousClassDeclaration();
        if (anonymousDecl == null) {
            throw new RuntimeException("No anonymous class declaration found");
        }

        @SuppressWarnings("unchecked")
        List<BodyDeclaration> bodyDecls = anonymousDecl.bodyDeclarations();
        MethodDeclaration singleMethod = null;
        int methodCount = 0;

        for (BodyDeclaration bodyDecl : bodyDecls) {
            if (bodyDecl instanceof MethodDeclaration) {
                singleMethod = (MethodDeclaration) bodyDecl;
                methodCount++;
            }
        }

        if (methodCount != 1) {
            throw new RuntimeException("Anonymous class must have exactly one method to convert to lambda");
        }

        if (singleMethod == null) {
            throw new RuntimeException("No method found in anonymous class");
        }

        StringBuilder lambda = new StringBuilder();

        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> lambdaParams = singleMethod.parameters();

        if (lambdaParams.isEmpty()) {
            lambda.append("()");
        } else if (lambdaParams.size() == 1) {
            lambda.append(lambdaParams.get(0).getName().getIdentifier());
        } else {
            lambda.append("(");
            for (int i = 0; i < lambdaParams.size(); i++) {
                if (i > 0) {
                    lambda.append(", ");
                }
                lambda.append(lambdaParams.get(i).getName().getIdentifier());
            }
            lambda.append(")");
        }

        lambda.append(" -> ");

        Block body = singleMethod.getBody();
        if (body == null) {
            throw new RuntimeException("Method has no body");
        }

        @SuppressWarnings("unchecked")
        List<Statement> statements = body.statements();

        if (statements.size() == 1) {
            Statement stmt = statements.get(0);
            if (stmt instanceof ReturnStatement) {
                ReturnStatement retStmt = (ReturnStatement) stmt;
                Expression retExpr = retStmt.getExpression();
                if (retExpr != null) {
                    lambda.append(source, retExpr.getStartPosition(),
                            retExpr.getStartPosition() + retExpr.getLength());
                } else {
                    lambda.append("{}");
                }
            } else if (stmt instanceof ExpressionStatement) {
                ExpressionStatement exprStmt = (ExpressionStatement) stmt;
                Expression expr = exprStmt.getExpression();
                lambda.append(source, expr.getStartPosition(),
                        expr.getStartPosition() + expr.getLength());
            } else {
                lambda.append("{\n");
                lambda.append("\t\t").append(source.substring(stmt.getStartPosition(),
                        stmt.getStartPosition() + stmt.getLength()).trim());
                lambda.append("\n\t}");
            }
        } else {
            lambda.append("{\n");
            for (Statement stmt : statements) {
                lambda.append("\t\t").append(source.substring(stmt.getStartPosition(),
                        stmt.getStartPosition() + stmt.getLength()).trim()).append("\n");
            }
            lambda.append("\t}");
        }

        int creationStart = creation.getStartPosition();
        int creationEnd = creationStart + creation.getLength();

        String newSource = source.substring(0, creationStart) + lambda.toString() + source.substring(creationEnd);

        List<Map<String, Object>> edits = createWholeFileEdit(uri, source, newSource);
        return createSuccessResult(edits);
    }

    private ClassInstanceCreation findClassInstanceCreation(CompilationUnit ast, int offset) {
        final ClassInstanceCreation[] result = new ClassInstanceCreation[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(ClassInstanceCreation node) {
                if (node.getAnonymousClassDeclaration() != null) {
                    int start = node.getStartPosition();
                    int end = start + node.getLength();
                    if (start <= offset && end >= offset) {
                        if (result[0] == null || node.getLength() < result[0].getLength()) {
                            result[0] = node;
                        }
                    }
                }
                return true;
            }
        });
        return result[0];
    }
}
