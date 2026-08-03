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
package com.ibm.mcp.jdtls.handlers.quality;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.findLargeClasses" command.
 *
 * <p>Arguments: [{maxMethods (optional, default 20), maxFields (optional, default 20),
 * maxLoc (optional, default 500)}]</p>
 *
 * <p>Scans all source types in the workspace using the Java model (no AST parsing)
 * and filters types exceeding any threshold.</p>
 */
public class FindLargeClassesHandler implements ICommandHandler {

    private static final int DEFAULT_MAX_METHODS = 20;
    private static final int DEFAULT_MAX_FIELDS = 20;
    private static final int DEFAULT_MAX_LOC = 500;

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        int maxMethods = DEFAULT_MAX_METHODS;
        int maxFields = DEFAULT_MAX_FIELDS;
        int maxLoc = DEFAULT_MAX_LOC;

        if (arguments != null && !arguments.isEmpty()) {
            Map<String, Object> params = (Map<String, Object>) arguments.get(0);
            if (params.containsKey("maxMethods")) {
                maxMethods = ((Number) params.get("maxMethods")).intValue();
            }
            if (params.containsKey("maxFields")) {
                maxFields = ((Number) params.get("maxFields")).intValue();
            }
            if (params.containsKey("maxLoc")) {
                maxLoc = ((Number) params.get("maxLoc")).intValue();
            }
        }

        List<Map<String, Object>> largeClasses = new ArrayList<>();

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
                            analyzeTypes(cu, maxMethods, maxFields, maxLoc, largeClasses, monitor);
                        }
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("largeClasses", largeClasses);
        result.put("count", largeClasses.size());
        return result;
    }

    private void analyzeTypes(ICompilationUnit cu, int thresholdMethods,
            int thresholdFields, int thresholdLoc, List<Map<String, Object>> largeClasses,
            IProgressMonitor monitor) throws Exception {
        String source = null;

        for (IType type : cu.getAllTypes()) {
            if (monitor != null && monitor.isCanceled()) {
                break;
            }

            int methodCount = type.getMethods().length;
            int fieldCount = type.getFields().length;

            int loc = 0;
            ISourceRange range = type.getSourceRange();
            if (range != null && range.getLength() > 0) {
                if (source == null) {
                    source = cu.getSource();
                }
                if (source != null) {
                    loc = countLines(source, range.getOffset(), range.getLength());
                }
            }

            if (methodCount > thresholdMethods || fieldCount > thresholdFields
                    || loc > thresholdLoc) {
                Map<String, Object> classInfo = new HashMap<>();
                classInfo.put("name", type.getElementName());
                classInfo.put("fqn", type.getFullyQualifiedName());
                if (cu.getResource() != null) {
                    classInfo.put("uri", JdtUtils.toFileUri(cu.getResource()));
                }
                classInfo.put("methods", methodCount);
                classInfo.put("fields", fieldCount);
                classInfo.put("loc", loc);
                largeClasses.add(classInfo);
            }
        }
    }

    private int countLines(String source, int offset, int length) {
        int end = Math.min(offset + length, source.length());
        int lines = 1;
        for (int i = offset; i < end; i++) {
            if (source.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
