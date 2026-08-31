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
package org.eclipse.mcp.jdtls.handlers.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
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
 * Handler for "mcp.jdtls.findAffectedTests" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the element at the given position, then transitively finds all
 * references (up to 2 levels deep) and filters for test methods (annotated with
 * JUnit 4/5 or TestNG test annotations).</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindAffectedTestsTool.java">javalens-mcp FindAffectedTestsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindAffectedTestsHandler implements ICommandHandler {

    private static final int MAX_DEPTH = 2;
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory"
    );

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

        int maxResults = 0;
        Object mr = params.get("maxResults");
        if (mr instanceof Number n) {
            maxResults = n.intValue();
        }
        final int limit = maxResults > 0 ? maxResults : 50;

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);
        if (elements.length == 0) {
            throw new RuntimeException("No element found at position");
        }

        IJavaElement targetElement = elements[0];
        String elementName = targetElement.getElementName();

        // BFS to find all referencing elements up to MAX_DEPTH levels
        Set<String> visited = new HashSet<>();
        visited.add(targetElement.getHandleIdentifier());
        Queue<IJavaElement> queue = new LinkedList<>();
        queue.add(targetElement);

        Set<IMethod> candidateMethods = new HashSet<>();
        int depth = 0;
        boolean truncated = false;

        while (!queue.isEmpty() && depth < MAX_DEPTH) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                if (monitor.isCanceled()) {
                    break;
                }
                if (candidateMethods.size() >= limit * 3) {
                    truncated = true;
                    break;
                }
                IJavaElement current = queue.poll();
                SearchPattern pattern = SearchPattern.createPattern(current, IJavaSearchConstants.REFERENCES);
                if (pattern == null) {
                    continue;
                }

                IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
                SearchEngine engine = new SearchEngine();
                try {
                    engine.search(
                            pattern,
                            new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                            scope,
                            new SearchRequestor() {
                                @Override
                                public void acceptSearchMatch(SearchMatch match) {
                                    if (match.getElement() instanceof IJavaElement refElement) {
                                        if (visited.add(refElement.getHandleIdentifier())) {
                                            queue.add(refElement);
                                            IMethod method = null;
                                            if (refElement instanceof IMethod) {
                                                method = (IMethod) refElement;
                                            } else {
                                                IJavaElement ancestor = refElement.getAncestor(IJavaElement.METHOD);
                                                if (ancestor instanceof IMethod) {
                                                    method = (IMethod) ancestor;
                                                }
                                            }
                                            if (method != null) {
                                                candidateMethods.add(method);
                                            }
                                        }
                                    }
                                }
                            },
                            monitor);
                } catch (Exception e) {
                    // Some element types may not support reference search; skip gracefully
                }
            }
            depth++;
        }

        // Filter candidate methods for test annotations
        List<Map<String, Object>> affectedTests = new ArrayList<>();
        for (IMethod method : candidateMethods) {
            if (monitor.isCanceled()) {
                break;
            }
            if (affectedTests.size() >= limit) {
                truncated = true;
                break;
            }
            if (isTestMethod(method)) {
                Map<String, Object> test = new HashMap<>();
                test.put("methodName", method.getElementName());
                IType declaringType = method.getDeclaringType();
                if (declaringType != null) {
                    test.put("className", declaringType.getFullyQualifiedName());
                }
                if (method.getResource() != null) {
                    test.put("uri", JdtUtils.toFileUri(method.getResource()));
                }
                affectedTests.add(test);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("element", elementName);
        result.put("count", affectedTests.size());
        if (truncated) {
            result.put("truncated", true);
        }
        result.put("affectedTests", affectedTests);
        return result;
    }

    private boolean isTestMethod(IMethod method) {
        ICompilationUnit cu = method.getCompilationUnit();
        if (cu == null) {
            return false;
        }

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit astRoot = (CompilationUnit) parser.createAST(null);

        boolean[] found = {false};
        String methodName = method.getElementName();
        int paramCount = method.getNumberOfParameters();

        astRoot.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getName().getIdentifier().equals(methodName)
                        && node.parameters().size() == paramCount) {
                    for (Object modifier : node.modifiers()) {
                        String annotationName = null;
                        if (modifier instanceof MarkerAnnotation annotation) {
                            annotationName = annotation.getTypeName().getFullyQualifiedName();
                        } else if (modifier instanceof NormalAnnotation annotation) {
                            annotationName = annotation.getTypeName().getFullyQualifiedName();
                        } else if (modifier instanceof SingleMemberAnnotation annotation) {
                            annotationName = annotation.getTypeName().getFullyQualifiedName();
                        }
                        if (annotationName != null) {
                            String simpleName = annotationName.contains(".")
                                    ? annotationName.substring(annotationName.lastIndexOf('.') + 1)
                                    : annotationName;
                            if (TEST_ANNOTATIONS.contains(simpleName)) {
                                found[0] = true;
                            }
                        }
                    }
                }
                return false;
            }
        });

        return found[0];
    }
}
