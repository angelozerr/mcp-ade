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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getDependencyGraph" command.
 *
 * <p>Arguments: [{uri}] for a specific file, or no arguments for project-wide.</p>
 *
 * <p>Builds an import dependency graph by scanning all ICompilationUnit imports.
 * For each CU, extracts the package and all imported packages to build edges.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetDependencyGraphTool.java">javalens-mcp GetDependencyGraphTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetDependencyGraphHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        String uri = null;
        int maxEdges = 0;
        if (arguments != null && !arguments.isEmpty()) {
            Map<String, Object> params = (Map<String, Object>) arguments.get(0);
            uri = (String) params.get("uri");
            Object me = params.get("maxEdges");
            if (me instanceof Number n) {
                maxEdges = n.intValue();
            }
        }
        final int edgeLimit = maxEdges;

        Set<String> nodes = new LinkedHashSet<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        boolean[] truncated = {false};

        if (uri != null) {
            ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);
            collectDependencies(cu, nodes, edges, edgeKeys, edgeLimit, truncated);
        } else {
            IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : projects) {
                if (monitor != null && monitor.isCanceled()) {
                    break;
                }
                if (edgeLimit > 0 && edges.size() >= edgeLimit) {
                    truncated[0] = true;
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
                        if (edgeLimit > 0 && edges.size() >= edgeLimit) {
                            truncated[0] = true;
                            break;
                        }
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                if (edgeLimit > 0 && edges.size() >= edgeLimit) {
                                    truncated[0] = true;
                                    break;
                                }
                                collectDependencies(cu, nodes, edges, edgeKeys, edgeLimit, truncated);
                            }
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("nodes", new ArrayList<>(nodes));
        result.put("edges", edges);
        result.put("nodeCount", nodes.size());
        result.put("edgeCount", edges.size());
        if (truncated[0]) {
            result.put("truncated", true);
        }
        return result;
    }

    private void collectDependencies(ICompilationUnit cu, Set<String> nodes,
            List<Map<String, Object>> edges, Set<String> edgeKeys,
            int edgeLimit, boolean[] truncated) throws JavaModelException {
        if (edgeLimit > 0 && edges.size() >= edgeLimit) {
            truncated[0] = true;
            return;
        }
        String sourcePackage = getPackageName(cu);
        nodes.add(sourcePackage);

        IImportDeclaration[] imports = cu.getImports();
        for (IImportDeclaration imp : imports) {
            if (edgeLimit > 0 && edges.size() >= edgeLimit) {
                truncated[0] = true;
                return;
            }
            String importName = imp.getElementName();
            String targetPackage = extractPackage(importName, imp.isOnDemand());
            if (targetPackage != null && !targetPackage.equals(sourcePackage)) {
                nodes.add(targetPackage);
                String edgeKey = sourcePackage + "->" + targetPackage;
                if (edgeKeys.add(edgeKey)) {
                    Map<String, Object> edge = new HashMap<>();
                    edge.put("from", sourcePackage);
                    edge.put("to", targetPackage);
                    edges.add(edge);
                }
            }
        }
    }

    private String getPackageName(ICompilationUnit cu) throws JavaModelException {
        if (cu.getPackageDeclarations().length > 0) {
            return cu.getPackageDeclarations()[0].getElementName();
        }
        return "(default)";
    }

    private String extractPackage(String importName, boolean onDemand) {
        if (onDemand) {
            // import com.example.* -> package is com.example
            return importName;
        }
        int lastDot = importName.lastIndexOf('.');
        if (lastDot > 0) {
            return importName.substring(0, lastDot);
        }
        return null;
    }
}
