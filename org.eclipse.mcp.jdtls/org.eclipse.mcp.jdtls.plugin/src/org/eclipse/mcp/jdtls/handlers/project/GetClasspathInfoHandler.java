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
package org.eclipse.mcp.jdtls.handlers.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.mcp.jdtls.ICommandHandler;

/**
 * Handler for "mcp.jdtls.getClasspathInfo" command.
 *
 * <p>Arguments: none (uses first Java project in workspace)</p>
 *
 * <p>Retrieves both raw and resolved classpath entries for the project,
 * including source, library, container, project, and variable entries.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetClasspathInfoTool.java">javalens-mcp GetClasspathInfoTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetClasspathInfoHandler implements ICommandHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        IJavaProject javaProject = findFirstJavaProject();
        if (javaProject == null) {
            throw new RuntimeException("No Java project found in workspace");
        }

        List<Map<String, Object>> entries = new ArrayList<>();

        // Get resolved classpath to include expanded containers and variables
        IClasspathEntry[] resolvedEntries = javaProject.getResolvedClasspath(true);

        for (IClasspathEntry entry : resolvedEntries) {
            if (monitor != null && monitor.isCanceled()) {
                break;
            }

            Map<String, Object> entryInfo = new HashMap<>();
            entryInfo.put("kind", getEntryKindName(entry.getEntryKind()));
            entryInfo.put("path", entry.getPath().toString());

            // Source attachment (for libraries)
            if (entry.getSourceAttachmentPath() != null) {
                entryInfo.put("sourcePath", entry.getSourceAttachmentPath().toString());
            }

            // Output location (for source entries)
            if (entry.getOutputLocation() != null) {
                entryInfo.put("outputLocation", entry.getOutputLocation().toString());
            }

            // Exported flag
            entryInfo.put("exported", entry.isExported());

            entries.add(entryInfo);
        }

        // Also include raw classpath for containers and variables
        List<Map<String, Object>> rawEntries = new ArrayList<>();
        IClasspathEntry[] raw = javaProject.getRawClasspath();
        for (IClasspathEntry entry : raw) {
            Map<String, Object> entryInfo = new HashMap<>();
            entryInfo.put("kind", getEntryKindName(entry.getEntryKind()));
            entryInfo.put("path", entry.getPath().toString());
            rawEntries.add(entryInfo);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("project", javaProject.getElementName());
        result.put("entries", entries);
        result.put("rawEntries", rawEntries);
        result.put("count", entries.size());
        result.put("defaultOutputLocation", javaProject.getOutputLocation().toString());
        return result;
    }

    private String getEntryKindName(int kind) {
        switch (kind) {
            case IClasspathEntry.CPE_SOURCE:
                return "SOURCE";
            case IClasspathEntry.CPE_LIBRARY:
                return "LIBRARY";
            case IClasspathEntry.CPE_CONTAINER:
                return "CONTAINER";
            case IClasspathEntry.CPE_PROJECT:
                return "PROJECT";
            case IClasspathEntry.CPE_VARIABLE:
                return "VARIABLE";
            default:
                return "UNKNOWN";
        }
    }

    private IJavaProject findFirstJavaProject() {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject project : projects) {
            try {
                if (project.isOpen() && project.hasNature(JavaCore.NATURE_ID)) {
                    return JavaCore.create(project);
                }
            } catch (Exception e) {
                // Skip projects that cannot be processed
            }
        }
        return null;
    }
}
