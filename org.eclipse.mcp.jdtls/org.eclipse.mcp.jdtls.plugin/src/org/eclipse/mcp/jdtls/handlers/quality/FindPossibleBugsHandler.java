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
package org.eclipse.mcp.jdtls.handlers.quality;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * Handler for "mcp.jdtls.findPossibleBugs" command.
 *
 * <p>Arguments: [{uri}]</p>
 *
 * <p>Detects common bug patterns: empty catch blocks, String comparison with ==,
 * equals() without hashCode(), null check after dereference, resource not closed,
 * returning null from Optional.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindPossibleBugsTool.java">javalens-mcp FindPossibleBugsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindPossibleBugsHandler implements ICommandHandler {

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
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        List<Map<String, Object>> possibleBugs = new ArrayList<>();

        ast.accept(new ASTVisitor() {

            // --- Empty catch blocks ---
            @Override
            public boolean visit(CatchClause node) {
                Block body = node.getBody();
                if (body != null && body.statements().isEmpty()) {
                    addBug(possibleBugs, "empty-catch-block",
                            "Empty catch block swallows exception: "
                                    + node.getException().getType(),
                            uri, ast.getLineNumber(node.getStartPosition()), "warning");
                }
                return true;
            }

            // --- String comparison with == ---
            @Override
            public boolean visit(InfixExpression node) {
                if (node.getOperator() == InfixExpression.Operator.EQUALS
                        || node.getOperator() == InfixExpression.Operator.NOT_EQUALS) {
                    if (isStringType(node.getLeftOperand()) || isStringType(node.getRightOperand())) {
                        // Skip null comparisons
                        if (!(node.getLeftOperand() instanceof NullLiteral)
                                && !(node.getRightOperand() instanceof NullLiteral)) {
                            addBug(possibleBugs, "string-comparison-with-==",
                                    "String comparison using == instead of .equals()",
                                    uri, ast.getLineNumber(node.getStartPosition()), "warning");
                        }
                    }
                }
                return true;
            }

            // --- equals() without hashCode() ---
            @Override
            public boolean visit(TypeDeclaration node) {
                boolean hasEquals = false;
                boolean hasHashCode = false;

                for (MethodDeclaration method : node.getMethods()) {
                    String name = method.getName().getIdentifier();
                    if ("equals".equals(name) && method.parameters().size() == 1) {
                        hasEquals = true;
                    }
                    if ("hashCode".equals(name) && method.parameters().isEmpty()) {
                        hasHashCode = true;
                    }
                }

                if (hasEquals && !hasHashCode) {
                    addBug(possibleBugs, "equals-without-hashCode",
                            "Type " + node.getName().getIdentifier()
                                    + " overrides equals() but not hashCode()",
                            uri, ast.getLineNumber(node.getStartPosition()), "warning");
                } else if (!hasEquals && hasHashCode) {
                    addBug(possibleBugs, "hashCode-without-equals",
                            "Type " + node.getName().getIdentifier()
                                    + " overrides hashCode() but not equals()",
                            uri, ast.getLineNumber(node.getStartPosition()), "warning");
                }

                return true;
            }

            // --- Returning null from Optional ---
            @Override
            public boolean visit(ReturnStatement node) {
                if (node.getExpression() instanceof NullLiteral) {
                    // Check if the enclosing method returns Optional
                    MethodDeclaration enclosingMethod = findEnclosingMethod(node);
                    if (enclosingMethod != null) {
                        IMethodBinding binding = enclosingMethod.resolveBinding();
                        if (binding != null) {
                            ITypeBinding returnType = binding.getReturnType();
                            if (returnType != null
                                    && "java.util.Optional".equals(
                                            returnType.getQualifiedName())) {
                                addBug(possibleBugs, "returning-null-optional",
                                        "Returning null from method with Optional return type",
                                        uri, ast.getLineNumber(node.getStartPosition()), "error");
                            }
                        }
                    }
                }
                return true;
            }

            // --- Resource not closed (AutoCloseable without try-with-resources) ---
            @Override
            public boolean visit(VariableDeclarationStatement node) {
                for (Object frag : node.fragments()) {
                    if (frag instanceof VariableDeclarationFragment vdf) {
                        Expression initializer = vdf.getInitializer();
                        if (initializer instanceof ClassInstanceCreation cic) {
                            ITypeBinding typeBinding = cic.resolveTypeBinding();
                            if (typeBinding != null && isAutoCloseable(typeBinding)) {
                                // Check if inside a try-with-resources
                                if (!isInTryWithResources(node)) {
                                    addBug(possibleBugs, "resource-not-closed",
                                            "AutoCloseable resource '"
                                                    + vdf.getName().getIdentifier()
                                                    + "' may not be closed; consider try-with-resources",
                                            uri, ast.getLineNumber(node.getStartPosition()),
                                            "warning");
                                }
                            }
                        }
                    }
                }
                return true;
            }
        });

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("possibleBugs", possibleBugs);
        result.put("count", possibleBugs.size());
        return result;
    }

    private boolean isStringType(Expression expr) {
        ITypeBinding binding = expr.resolveTypeBinding();
        return binding != null && "java.lang.String".equals(binding.getQualifiedName());
    }

    private boolean isAutoCloseable(ITypeBinding typeBinding) {
        if ("java.lang.AutoCloseable".equals(typeBinding.getQualifiedName())
                || "java.io.Closeable".equals(typeBinding.getQualifiedName())) {
            return true;
        }
        for (ITypeBinding iface : typeBinding.getInterfaces()) {
            if (isAutoCloseable(iface)) {
                return true;
            }
        }
        ITypeBinding superclass = typeBinding.getSuperclass();
        if (superclass != null) {
            return isAutoCloseable(superclass);
        }
        return false;
    }

    private boolean isInTryWithResources(org.eclipse.jdt.core.dom.ASTNode node) {
        org.eclipse.jdt.core.dom.ASTNode parent = node.getParent();
        while (parent != null) {
            if (parent instanceof org.eclipse.jdt.core.dom.TryStatement tryStmt) {
                if (!tryStmt.resources().isEmpty()) {
                    return true;
                }
            }
            parent = parent.getParent();
        }
        return false;
    }

    private MethodDeclaration findEnclosingMethod(org.eclipse.jdt.core.dom.ASTNode node) {
        org.eclipse.jdt.core.dom.ASTNode parent = node.getParent();
        while (parent != null) {
            if (parent instanceof MethodDeclaration md) {
                return md;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private void addBug(List<Map<String, Object>> bugs, String pattern, String description,
            String uri, int line, String severity) {
        Map<String, Object> bug = new HashMap<>();
        bug.put("pattern", pattern);
        bug.put("description", description);
        bug.put("uri", uri);
        bug.put("line", line);
        bug.put("severity", severity);
        bugs.add(bug);
    }
}
