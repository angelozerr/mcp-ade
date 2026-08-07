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
package com.ibm.mcp.jdtls.handlers.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.findReflectionUsage" command.
 *
 * <p>Arguments: [{uri}]</p>
 *
 * <p>Uses ASTParser to find reflection API usages including:</p>
 * <ul>
 *   <li>{@code Class.forName()}</li>
 *   <li>{@code Class.getMethod()} / {@code Class.getDeclaredMethod()}</li>
 *   <li>{@code Field.get()} / {@code Field.set()}</li>
 *   <li>{@code Method.invoke()}</li>
 *   <li>{@code Constructor.newInstance()}</li>
 *   <li>Import declarations for {@code java.lang.reflect.*}</li>
 * </ul>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindReflectionUsageTool.java">javalens-mcp FindReflectionUsageTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindReflectionUsageHandler implements ICommandHandler {

    private static final Set<String> REFLECTION_TYPES = Set.of(
            "java.lang.Class",
            "java.lang.reflect.Method",
            "java.lang.reflect.Field",
            "java.lang.reflect.Constructor"
    );

    private static final Set<String> REFLECTION_METHODS = Set.of(
            "forName", "getMethod", "getDeclaredMethod", "getDeclaredMethods",
            "getField", "getDeclaredField", "getDeclaredFields",
            "getConstructor", "getDeclaredConstructor", "getDeclaredConstructors",
            "get", "set", "getInt", "setInt", "getLong", "setLong",
            "getFloat", "setFloat", "getDouble", "setDouble", "getBoolean", "setBoolean",
            "invoke", "newInstance"
    );

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }
        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");
        if (uri == null) {
            throw new RuntimeException("Missing uri parameter");
        }

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit astRoot = (CompilationUnit) parser.createAST(monitor);

        List<Map<String, Object>> reflectionUsages = new ArrayList<>();

        astRoot.accept(new ASTVisitor() {
            @Override
            public boolean visit(ImportDeclaration node) {
                String importName = node.getName().getFullyQualifiedName();
                if (importName.startsWith("java.lang.reflect")) {
                    Map<String, Object> usage = new HashMap<>();
                    usage.put("kind", "import");
                    usage.put("expression", node.toString().trim());
                    usage.put("line", astRoot.getLineNumber(node.getStartPosition()) - 1);
                    usage.put("character", astRoot.getColumnNumber(node.getStartPosition()));
                    reflectionUsages.add(usage);
                }
                return false;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                IMethodBinding binding = node.resolveMethodBinding();
                if (binding != null) {
                    ITypeBinding declaringClass = binding.getDeclaringClass();
                    if (declaringClass != null) {
                        String qualifiedName = declaringClass.getQualifiedName();
                        String methodName = node.getName().getIdentifier();
                        if (REFLECTION_TYPES.contains(qualifiedName)
                                && REFLECTION_METHODS.contains(methodName)) {
                            Map<String, Object> usage = new HashMap<>();
                            usage.put("kind", "methodInvocation");
                            usage.put("method", qualifiedName + "." + methodName);
                            usage.put("expression", node.toString());
                            usage.put("line", astRoot.getLineNumber(node.getStartPosition()) - 1);
                            usage.put("character", astRoot.getColumnNumber(node.getStartPosition()));
                            reflectionUsages.add(usage);
                        }
                    }
                }
                return true;
            }
        });

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("count", reflectionUsages.size());
        result.put("reflectionUsages", reflectionUsages);
        return result;
    }
}
