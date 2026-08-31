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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.findTests" command.
 *
 * <p>Arguments: [{uri}] for a single file, or [{}] / no arguments for
 * workspace-wide search.</p>
 *
 * <p>Uses ASTParser to find methods annotated with JUnit 4/5 or TestNG test
 * annotations.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindTestsTool.java">javalens-mcp FindTestsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindTestsHandler implements ICommandHandler {

    private static final Set<String> TEST_ANNOTATIONS = new HashSet<>();

    static {
        // JUnit 5
        TEST_ANNOTATIONS.add("Test");
        TEST_ANNOTATIONS.add("ParameterizedTest");
        TEST_ANNOTATIONS.add("RepeatedTest");
        TEST_ANNOTATIONS.add("TestFactory");
        // JUnit 4 and TestNG use @Test as well (covered by "Test" above)
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        List<Map<String, Object>> tests = new ArrayList<>();

        String uri = null;
        if (arguments != null && !arguments.isEmpty()) {
            Object firstArg = arguments.get(0);
            if (firstArg instanceof Map) {
                uri = (String) ((Map<String, Object>) firstArg).get("uri");
            }
        }

        if (uri != null) {
            // Single file search
            ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);
            collectTests(cu, uri, tests);
        } else {
            // Workspace-wide search
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (monitor.isCanceled()) {
                    break;
                }
                IJavaProject javaProject = JavaCore.create(project);
                if (javaProject == null || !javaProject.exists()) {
                    continue;
                }
                for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
                    if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                        continue;
                    }
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                if (monitor.isCanceled()) {
                                    break;
                                }
                                String cuUri = JdtUtils.toFileUri(cu.getResource());
                                collectTests(cu, cuUri, tests);
                            }
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("count", tests.size());
        result.put("tests", tests);
        return result;
    }

    private void collectTests(ICompilationUnit cu, String uri, List<Map<String, Object>> tests) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit astRoot = (CompilationUnit) parser.createAST(null);

        astRoot.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                for (Object modifier : node.modifiers()) {
                    String annotationName = null;
                    if (modifier instanceof MarkerAnnotation annotation) {
                        annotationName = annotation.getTypeName().getFullyQualifiedName();
                    } else if (modifier instanceof NormalAnnotation annotation) {
                        annotationName = annotation.getTypeName().getFullyQualifiedName();
                    } else if (modifier instanceof SingleMemberAnnotation annotation) {
                        annotationName = annotation.getTypeName().getFullyQualifiedName();
                    }
                    if (annotationName != null && isTestAnnotation(annotationName)) {
                        Map<String, Object> test = new HashMap<>();
                        if (node.getParent() instanceof TypeDeclaration typeDecl) {
                            test.put("className", typeDecl.getName().getIdentifier());
                        }
                        test.put("methodName", node.getName().getIdentifier());
                        test.put("annotation", "@" + annotationName);
                        test.put("uri", uri);
                        test.put("line", astRoot.getLineNumber(node.getStartPosition()) - 1);
                        tests.add(test);
                        break;
                    }
                }
                return false;
            }
        });
    }

    private boolean isTestAnnotation(String name) {
        // Handle both simple name and fully qualified name
        String simpleName = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return TEST_ANNOTATIONS.contains(simpleName);
    }
}
