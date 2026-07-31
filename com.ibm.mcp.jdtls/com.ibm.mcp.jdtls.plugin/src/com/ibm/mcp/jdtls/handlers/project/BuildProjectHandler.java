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
package com.ibm.mcp.jdtls.handlers.project;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.JavaCore;

import com.ibm.mcp.jdtls.ICommandHandler;

/**
 * Handler for "mcp.jdtls.buildProject" command.
 *
 * <p>Builds a single project by name using only the JDT Java builder,
 * without triggering M2E or other builders that would cascade across
 * the entire workspace dependency chain.</p>
 *
 * <p>Arguments: [{projectName}]</p>
 */
public class BuildProjectHandler implements ICommandHandler {

    @SuppressWarnings("unchecked")
    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty() || !(arguments.get(0) instanceof Map)) {
            return Map.of("error", "Missing required argument: projectName");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String projectName = (String) params.get("projectName");

        if (projectName == null) {
            return Map.of("error", "projectName is required");
        }

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject(projectName);

        if (!project.exists() || !project.isOpen()) {
            return Map.of("error", "Project not found or not open: " + projectName);
        }

        long start = System.currentTimeMillis();
        // Run ONLY the JDT Java builder on this single project.
        // The generic project.build(kind, monitor) triggers Eclipse's BuildManager
        // which cascades across all dependent projects and runs M2E builders.
        project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD,
                JavaCore.BUILDER_ID, null, monitor);
        long elapsed = System.currentTimeMillis() - start;

        Map<String, Object> result = new HashMap<>();
        result.put("status", "built");
        result.put("projectName", projectName);
        result.put("buildTimeMs", elapsed);
        return result;
    }
}
