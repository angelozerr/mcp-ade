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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

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
import org.eclipse.mcp.jdtls.ICommandHandler;

/**
 * Handler for "mcp.jdtls.findCircularDependencies" command.
 *
 * <p>Arguments: none (workspace-wide analysis).</p>
 *
 * <p>Builds a package dependency graph from imports across all CUs, then runs
 * Tarjan's strongly connected components algorithm to find cycles.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindCircularDependenciesTool.java">javalens-mcp FindCircularDependenciesTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindCircularDependenciesHandler implements ICommandHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        // Step 1: Build adjacency list
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();

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
                            collectDependencies(cu, adjacency);
                        }
                    }
                }
            }
        }

        // Step 2: Run Tarjan's SCC algorithm
        List<List<String>> cycles = findStronglyConnectedComponents(adjacency);

        // Filter to only SCCs with size > 1 (actual cycles)
        List<List<String>> actualCycles = new ArrayList<>();
        for (List<String> scc : cycles) {
            if (scc.size() > 1) {
                actualCycles.add(scc);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("cycles", actualCycles);
        result.put("count", actualCycles.size());
        return result;
    }

    private void collectDependencies(ICompilationUnit cu, Map<String, Set<String>> adjacency)
            throws JavaModelException {
        String sourcePackage = getPackageName(cu);
        adjacency.computeIfAbsent(sourcePackage, k -> new LinkedHashSet<>());

        IImportDeclaration[] imports = cu.getImports();
        for (IImportDeclaration imp : imports) {
            String importName = imp.getElementName();
            String targetPackage = extractPackage(importName, imp.isOnDemand());
            if (targetPackage != null && !targetPackage.equals(sourcePackage)) {
                adjacency.computeIfAbsent(sourcePackage, k -> new LinkedHashSet<>())
                        .add(targetPackage);
                // Ensure target package is in the graph even with no outgoing edges
                adjacency.computeIfAbsent(targetPackage, k -> new LinkedHashSet<>());
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
            return importName;
        }
        int lastDot = importName.lastIndexOf('.');
        if (lastDot > 0) {
            return importName.substring(0, lastDot);
        }
        return null;
    }

    /**
     * Tarjan's strongly connected components algorithm.
     */
    private List<List<String>> findStronglyConnectedComponents(Map<String, Set<String>> adjacency) {
        List<List<String>> result = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Map<String, Boolean> onStack = new HashMap<>();
        Stack<String> stack = new Stack<>();
        int[] counter = {0};

        for (String node : adjacency.keySet()) {
            if (!index.containsKey(node)) {
                strongConnect(node, adjacency, index, lowLink, onStack, stack, counter, result);
            }
        }

        return result;
    }

    private void strongConnect(String v, Map<String, Set<String>> adjacency,
            Map<String, Integer> index, Map<String, Integer> lowLink,
            Map<String, Boolean> onStack, Stack<String> stack, int[] counter,
            List<List<String>> result) {
        index.put(v, counter[0]);
        lowLink.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.put(v, true);

        Set<String> neighbors = adjacency.getOrDefault(v, Set.of());
        for (String w : neighbors) {
            if (!index.containsKey(w)) {
                // Only recurse if w is in our graph (source packages only)
                if (adjacency.containsKey(w)) {
                    strongConnect(w, adjacency, index, lowLink, onStack, stack, counter, result);
                    lowLink.put(v, Math.min(lowLink.get(v), lowLink.get(w)));
                }
            } else if (Boolean.TRUE.equals(onStack.get(w))) {
                lowLink.put(v, Math.min(lowLink.get(v), index.get(w)));
            }
        }

        // If v is a root node, pop the SCC
        if (lowLink.get(v).equals(index.get(v))) {
            List<String> scc = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.put(w, false);
                scc.add(w);
            } while (!w.equals(v));
            result.add(scc);
        }
    }
}
