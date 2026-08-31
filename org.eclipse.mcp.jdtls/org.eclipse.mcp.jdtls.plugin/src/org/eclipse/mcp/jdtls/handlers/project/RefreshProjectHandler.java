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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.mcp.jdtls.ICommandHandler;

/**
 * Handler for "mcp.jdtls.refreshProject" command.
 *
 * <p>Refreshes all open Java projects in the Eclipse workspace by calling
 * {@code project.refreshLocal(DEPTH_INFINITE)}, which synchronizes the
 * Eclipse workspace model with the file system.</p>
 *
 * <p>This is needed when an AI agent creates/modifies/deletes files on disk
 * without going through LSP (e.g., using Write/Edit tools). Without this
 * refresh, JDT.LS doesn't know about the new files and operations like
 * build or debug fail with ClassNotFoundException.</p>
 */
public class RefreshProjectHandler implements ICommandHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        int refreshedCount = 0;

        for (IProject project : projects) {
            if (monitor != null && monitor.isCanceled()) {
                break;
            }
            if (!project.isOpen()) {
                continue;
            }
            try {
                if (project.hasNature(JavaCore.NATURE_ID)) {
                    project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                    refreshedCount++;
                }
            } catch (Exception e) {
                // Best-effort: continue with other projects
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("refreshedProjects", refreshedCount);
        return result;
    }
}
