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

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.callHierarchyIncoming" and "mcp.jdtls.callHierarchyOutgoing" commands.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Returns the call hierarchy (callers or callees) for the method at the given position.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetCallHierarchyIncomingTool.java">javalens-mcp GetCallHierarchyIncomingTool</a> and
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetCallHierarchyOutgoingTool.java">javalens-mcp GetCallHierarchyOutgoingTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class CallHierarchyHandler implements ICommandHandler {

    private final boolean incoming;

    public CallHierarchyHandler(boolean incoming) {
        this.incoming = incoming;
    }

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

        if (incoming) {
            return findCallers(method, arguments, monitor);
        } else {
            return findCallees(method, monitor);
        }
    }

    private Map<String, Object> findCallers(IMethod method, List<Object> arguments, IProgressMonitor monitor) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
                method,
                IJavaSearchConstants.REFERENCES);

        if (pattern == null) {
            return Map.of("method", method.getElementName(), "callers", List.of());
        }

        IJavaSearchScope scope = JdtUtils.resolveSearchScope(arguments);
        List<Map<String, Object>> callers = new ArrayList<>();
        Map<IResource, String> sourceCache = new HashMap<>();

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
                            callers.add(formatMember(member, match, sourceCache));
                        }
                    }
                },
                monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("method", method.getElementName());
        result.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
        result.put("count", callers.size());
        result.put("callers", JdtUtils.groupResultsByUri(callers));
        return result;
    }

    private Map<String, Object> findCallees(IMethod method, IProgressMonitor monitor) throws Exception {
        ICompilationUnit cu = method.getCompilationUnit();
        if (cu == null) {
            return Map.of("method", method.getElementName(), "callees", List.of(),
                    "error", "Method is not in a source compilation unit");
        }

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        // Find the MethodDeclaration AST node for this IMethod
        org.eclipse.jdt.core.ISourceRange sourceRange = method.getSourceRange();
        MethodDeclaration methodDecl = null;
        if (sourceRange != null) {
            org.eclipse.jdt.core.dom.ASTNode node = NodeFinder.perform(ast, sourceRange.getOffset(), sourceRange.getLength());
            while (node != null && !(node instanceof MethodDeclaration)) {
                node = node.getParent();
            }
            if (node instanceof MethodDeclaration) {
                methodDecl = (MethodDeclaration) node;
            }
        }

        if (methodDecl == null) {
            return Map.of("method", method.getElementName(), "callees", List.of());
        }

        List<Map<String, Object>> callees = new ArrayList<>();

        methodDecl.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                IMethodBinding binding = node.resolveMethodBinding();
                if (binding != null) {
                    callees.add(formatBinding(binding, ast, node.getStartPosition()));
                }
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                IMethodBinding binding = node.resolveMethodBinding();
                if (binding != null) {
                    callees.add(formatBinding(binding, ast, node.getStartPosition()));
                }
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                IMethodBinding binding = node.resolveConstructorBinding();
                if (binding != null) {
                    callees.add(formatBinding(binding, ast, node.getStartPosition()));
                }
                return true;
            }
        });

        Map<String, Object> result = new HashMap<>();
        result.put("method", method.getElementName());
        result.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
        result.put("callees", callees);
        return result;
    }

    private Map<String, Object> formatBinding(IMethodBinding binding, CompilationUnit ast, int offset) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", binding.getName());
        ITypeBinding declaringClass = binding.getDeclaringClass();
        if (declaringClass != null) {
            info.put("declaringType", declaringClass.getQualifiedName());
        }
        info.put("line", ast.getLineNumber(offset) - 1);
        info.put("character", ast.getColumnNumber(offset));
        String[] paramTypes = new String[binding.getParameterTypes().length];
        for (int i = 0; i < paramTypes.length; i++) {
            paramTypes[i] = binding.getParameterTypes()[i].getName();
        }
        info.put("parameterTypes", List.of(paramTypes));
        info.put("returnType", binding.getReturnType().getName());
        return info;
    }

    private Map<String, Object> formatMember(IMember member, SearchMatch match,
            Map<IResource, String> sourceCache) {
        Map<String, Object> info = JdtUtils.formatSearchMatch(match, sourceCache);
        info.put("name", member.getElementName());
        if (member.getDeclaringType() != null) {
            info.put("declaringType", member.getDeclaringType().getFullyQualifiedName());
        }
        if (member.getResource() != null) {
            info.put("uri", JdtUtils.toFileUri(member.getResource()));
        }
        return info;
    }
}
