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
import java.util.regex.Pattern;

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
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.findNamingViolations" command.
 *
 * <p>Arguments: [{uri}] for a file, or no arguments for project-wide.</p>
 *
 * <p>Checks naming conventions: UpperCamelCase for types, lowerCamelCase for
 * methods/fields, UPPER_SNAKE_CASE for constants, lowercase for packages,
 * single uppercase letter or UpperCamelCase for type parameters.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindNamingViolationsTool.java">javalens-mcp FindNamingViolationsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindNamingViolationsHandler implements ICommandHandler {

    private static final Pattern UPPER_CAMEL_CASE = Pattern.compile("[A-Z][a-zA-Z0-9]*");
    private static final Pattern LOWER_CAMEL_CASE = Pattern.compile("[a-z][a-zA-Z0-9]*");
    private static final Pattern UPPER_SNAKE_CASE = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");
    private static final Pattern LOWERCASE_PACKAGE = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*");
    private static final Pattern TYPE_PARAM = Pattern.compile("[A-Z]([A-Z]?[a-zA-Z0-9]*)?");

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        String uri = null;
        if (arguments != null && !arguments.isEmpty()) {
            Map<String, Object> params = (Map<String, Object>) arguments.get(0);
            uri = (String) params.get("uri");
        }

        List<Map<String, Object>> violations = new ArrayList<>();

        if (uri != null) {
            ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);
            analyzeCompilationUnit(cu, violations, monitor);
        } else {
            // Project-wide analysis
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (monitor != null && monitor.isCanceled()) {
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
                        if (monitor != null && monitor.isCanceled()) {
                            break;
                        }
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                analyzeCompilationUnit(cu, violations, monitor);
                            }
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("violations", violations);
        result.put("count", violations.size());
        return result;
    }

    private void analyzeCompilationUnit(ICompilationUnit cu, List<Map<String, Object>> violations,
            IProgressMonitor monitor) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        String fileUri = cu.getResource() != null
                ? JdtUtils.toFileUri(cu.getResource()) : null;

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(PackageDeclaration node) {
                String name = node.getName().getFullyQualifiedName();
                if (!LOWERCASE_PACKAGE.matcher(name).matches()) {
                    addViolation(violations, "package", name,
                            "all lowercase (e.g., com.example.mypackage)",
                            fileUri, ast.getLineNumber(node.getStartPosition()));
                }
                return true;
            }

            @Override
            public boolean visit(TypeDeclaration node) {
                String name = node.getName().getIdentifier();
                if (!UPPER_CAMEL_CASE.matcher(name).matches()) {
                    addViolation(violations, "type", name,
                            "UpperCamelCase (e.g., MyClass)",
                            fileUri, ast.getLineNumber(node.getStartPosition()));
                }

                // Check type parameters
                for (Object tp : node.typeParameters()) {
                    if (tp instanceof TypeParameter typeParam) {
                        String tpName = typeParam.getName().getIdentifier();
                        if (!TYPE_PARAM.matcher(tpName).matches()) {
                            addViolation(violations, "typeParameter", tpName,
                                    "single uppercase letter or UpperCamelCase (e.g., T, E, KeyType)",
                                    fileUri, ast.getLineNumber(typeParam.getStartPosition()));
                        }
                    }
                }
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                String name = node.getName().getIdentifier();
                // Skip constructors
                if (!node.isConstructor() && !LOWER_CAMEL_CASE.matcher(name).matches()) {
                    addViolation(violations, "method", name,
                            "lowerCamelCase (e.g., myMethod)",
                            fileUri, ast.getLineNumber(node.getStartPosition()));
                }

                // Check parameters
                for (Object param : node.parameters()) {
                    if (param instanceof SingleVariableDeclaration svd) {
                        String paramName = svd.getName().getIdentifier();
                        if (!LOWER_CAMEL_CASE.matcher(paramName).matches()) {
                            addViolation(violations, "parameter", paramName,
                                    "lowerCamelCase (e.g., myParam)",
                                    fileUri, ast.getLineNumber(svd.getStartPosition()));
                        }
                    }
                }
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                boolean isConstant = Modifier.isStatic(node.getModifiers())
                        && Modifier.isFinal(node.getModifiers());

                for (Object frag : node.fragments()) {
                    if (frag instanceof VariableDeclarationFragment vdf) {
                        String name = vdf.getName().getIdentifier();
                        if (isConstant) {
                            if (!UPPER_SNAKE_CASE.matcher(name).matches()) {
                                addViolation(violations, "constant", name,
                                        "UPPER_SNAKE_CASE (e.g., MAX_VALUE)",
                                        fileUri, ast.getLineNumber(vdf.getStartPosition()));
                            }
                        } else {
                            if (!LOWER_CAMEL_CASE.matcher(name).matches()) {
                                addViolation(violations, "field", name,
                                        "lowerCamelCase (e.g., myField)",
                                        fileUri, ast.getLineNumber(vdf.getStartPosition()));
                            }
                        }
                    }
                }
                return true;
            }
        });
    }

    private void addViolation(List<Map<String, Object>> violations,
            String kind, String name, String expectedPattern, String uri, int line) {
        Map<String, Object> violation = new HashMap<>();
        violation.put("kind", kind);
        violation.put("name", name);
        violation.put("expectedPattern", expectedPattern);
        violation.put("uri", uri);
        violation.put("line", line);
        violations.add(violation);
    }
}
