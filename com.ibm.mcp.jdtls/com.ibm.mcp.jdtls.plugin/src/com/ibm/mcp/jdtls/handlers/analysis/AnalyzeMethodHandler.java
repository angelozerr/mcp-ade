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
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.analyzeMethod" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Comprehensive method analysis: complexity, LOC, callers (via SearchEngine),
 * callees (via ASTVisitor), and override information.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/AnalyzeMethodTool.java">javalens-mcp AnalyzeMethodTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class AnalyzeMethodHandler implements ICommandHandler {

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

        // Parse AST
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        // Find the MethodDeclaration
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

        // Compute complexity and LOC from AST
        int complexity = 1;
        int loc = 0;
        List<Map<String, Object>> callees = new ArrayList<>();

        if (foundMethod[0] != null) {
            MethodDeclaration methodDecl = foundMethod[0];
            int startLine = ast.getLineNumber(methodDecl.getStartPosition());
            int endLine = ast.getLineNumber(methodDecl.getStartPosition() + methodDecl.getLength());
            loc = endLine - startLine + 1;

            // Compute cyclomatic complexity
            int[] cc = {1};
            methodDecl.accept(new ASTVisitor() {
                @Override
                public boolean visit(IfStatement n) { cc[0]++; return true; }
                @Override
                public boolean visit(ForStatement n) { cc[0]++; return true; }
                @Override
                public boolean visit(EnhancedForStatement n) { cc[0]++; return true; }
                @Override
                public boolean visit(WhileStatement n) { cc[0]++; return true; }
                @Override
                public boolean visit(DoStatement n) { cc[0]++; return true; }
                @Override
                public boolean visit(SwitchCase n) {
                    if (!n.isDefault()) cc[0]++;
                    return true;
                }
                @Override
                public boolean visit(CatchClause n) { cc[0]++; return true; }
                @Override
                public boolean visit(ConditionalExpression n) { cc[0]++; return true; }
            });
            complexity = cc[0];

            // Find callees (MethodInvocation nodes in the body)
            methodDecl.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodInvocation node) {
                    Map<String, Object> callee = new HashMap<>();
                    callee.put("name", node.getName().getIdentifier());
                    callee.put("line", ast.getLineNumber(node.getStartPosition()));
                    IMethodBinding binding = node.resolveMethodBinding();
                    if (binding != null && binding.getDeclaringClass() != null) {
                        callee.put("declaringType", binding.getDeclaringClass().getQualifiedName());
                    }
                    callees.add(callee);
                    return true;
                }
            });
        }

        // Find callers using SearchEngine
        List<Map<String, Object>> callers = findCallers(method, monitor);

        // Check overrides
        Map<String, Object> overrides = findOverridden(method);

        Map<String, Object> result = new HashMap<>();
        result.put("method", method.getElementName());
        result.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
        result.put("complexity", complexity);
        result.put("loc", loc);
        result.put("callers", callers);
        result.put("callees", callees);
        result.put("overrides", overrides);
        return result;
    }

    private List<Map<String, Object>> findCallers(IMethod method, IProgressMonitor monitor) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
                method,
                IJavaSearchConstants.REFERENCES);

        if (pattern == null) {
            return List.of();
        }

        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        List<Map<String, Object>> callers = new ArrayList<>();

        SearchEngine engine = new SearchEngine();
        engine.search(
                pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        Object element = match.getElement();
                        if (element instanceof IMember member) {
                            Map<String, Object> caller = new HashMap<>();
                            caller.put("name", member.getElementName());
                            if (member.getDeclaringType() != null) {
                                caller.put("declaringType", member.getDeclaringType().getFullyQualifiedName());
                            }
                            if (member.getResource() != null) {
                                caller.put("uri", JdtUtils.toFileUri(member.getResource()));
                            }
                            callers.add(caller);
                        }
                    }
                },
                monitor);

        return callers;
    }

    private Map<String, Object> findOverridden(IMethod method) throws JavaModelException {
        IType declaringType = method.getDeclaringType();
        if (declaringType == null) {
            return null;
        }

        String methodName = method.getElementName();
        String[] paramTypes = method.getParameterTypes();

        // Check supertypes
        String superclassName = declaringType.getSuperclassName();
        if (superclassName != null) {
            IType[] resolvedTypes = declaringType.newSupertypeHierarchy(null).getAllSuperclasses(declaringType);
            for (IType superType : resolvedTypes) {
                IMethod superMethod = superType.getMethod(methodName, paramTypes);
                if (superMethod != null && superMethod.exists()) {
                    Map<String, Object> info = new HashMap<>();
                    info.put("method", superMethod.getElementName());
                    info.put("declaringType", superType.getFullyQualifiedName());
                    return info;
                }
            }
        }

        // Check interfaces
        IType[] superInterfaces = declaringType.newSupertypeHierarchy(null).getAllSuperInterfaces(declaringType);
        for (IType iface : superInterfaces) {
            IMethod ifaceMethod = iface.getMethod(methodName, paramTypes);
            if (ifaceMethod != null && ifaceMethod.exists()) {
                Map<String, Object> info = new HashMap<>();
                info.put("method", ifaceMethod.getElementName());
                info.put("declaringType", iface.getFullyQualifiedName());
                return info;
            }
        }

        return null;
    }
}
