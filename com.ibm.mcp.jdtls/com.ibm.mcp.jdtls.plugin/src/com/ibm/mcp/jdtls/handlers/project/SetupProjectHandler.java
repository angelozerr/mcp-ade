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

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;

import com.ibm.mcp.jdtls.ICommandHandler;

/**
 * Handler for "mcp.jdtls.setupProject" command.
 *
 * <p>Creates a Java project in the JDT workspace with the specified classpath,
 * bypassing M2E/Gradle import for fast initialization.</p>
 *
 * <p>Arguments: [{projectName, projectPath, sourceRoots, classpathJars, projectReferences}]</p>
 * <ul>
 *   <li>{@code projectName} - name for the Eclipse project</li>
 *   <li>{@code projectPath} - absolute path to the project directory on disk</li>
 *   <li>{@code sourceRoots} - list of relative source folder paths (e.g., "src/main/java")</li>
 *   <li>{@code classpathJars} - list of absolute paths to dependency JARs</li>
 *   <li>{@code projectReferences} - (optional) list of project names to add as CPE_PROJECT references
 *       (used for reactor module dependencies)</li>
 *   <li>{@code disableBuilders} - (optional) if {@code true}, removes all builders from the project
 *       so JDT does not generate diagnostics; used for reactor dependency projects that only
 *       need to expose source types</li>
 * </ul>
 *
 * <p>Setting the classpath via {@code IJavaProject.setRawClasspath()} automatically
 * triggers the IndexManager to index sources and JARs in the background.</p>
 */
public class SetupProjectHandler implements ICommandHandler {

    @SuppressWarnings("unchecked")
    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty() || !(arguments.get(0) instanceof Map)) {
            return Map.of("error", "Missing required arguments: projectName, projectPath, sourceRoots, classpathJars");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String projectName = (String) params.get("projectName");
        String projectPath = (String) params.get("projectPath");
        List<String> sourceRoots = (List<String>) params.get("sourceRoots");
        List<String> classpathJars = (List<String>) params.get("classpathJars");
        List<String> projectReferences = (List<String>) params.get("projectReferences");
        Boolean disableBuilders = (Boolean) params.get("disableBuilders");

        if (projectName == null || projectPath == null) {
            return Map.of("error", "projectName and projectPath are required");
        }
        if (sourceRoots == null) {
            sourceRoots = List.of();
        }
        if (classpathJars == null) {
            classpathJars = List.of();
        }

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject project = root.getProject(projectName);

        // Check if another project already exists at this location (e.g., M2E imported
        // 'quarkus-ide-launcher' but the directory is named 'launcher')
        if (!project.exists()) {
            IProject existing = findProjectByLocation(root, projectPath);
            if (existing != null) {
                project = existing;
                projectName = existing.getName();
            }
        }

        boolean alreadyExists = project.exists() && project.isOpen()
                && JavaCore.create(project).exists();

        // Create the project with location pointing to the actual source directory
        if (!project.exists()) {
            IProjectDescription desc = root.getWorkspace().newProjectDescription(projectName);
            desc.setLocation(new Path(projectPath));
            desc.setNatureIds(new String[]{JavaCore.NATURE_ID});
            project.create(desc, monitor);
        }
        if (!project.isOpen()) {
            project.open(monitor);
        }

        // Force Java-only nature (strip Maven/PDE/Gradle natures from the on-disk .project)
        IProjectDescription desc = project.getDescription();
        String[] natures = desc.getNatureIds();
        boolean needsNatureUpdate = natures.length != 1 || !JavaCore.NATURE_ID.equals(natures[0]);
        if (needsNatureUpdate) {
            desc.setNatureIds(new String[]{JavaCore.NATURE_ID});
            project.setDescription(desc, monitor);
        }

        if (Boolean.TRUE.equals(disableBuilders)) {
            // For reactor dep projects: remove all builders to prevent JDT from
            // generating diagnostics. The Java nature is kept so CPE_PROJECT
            // references can resolve types from source.
            IProjectDescription buildDesc = project.getDescription();
            if (buildDesc.getBuildSpec().length > 0) {
                buildDesc.setBuildSpec(new org.eclipse.core.resources.ICommand[0]);
                project.setDescription(buildDesc, monitor);
            }
        } else {
            // Keep only Java builder (strip Maven/PDE builders from on-disk .project)
            IProjectDescription buildDesc = project.getDescription();
            org.eclipse.core.resources.ICommand[] buildSpec = buildDesc.getBuildSpec();
            boolean hasNonJavaBuilder = false;
            for (var cmd : buildSpec) {
                if (!JavaCore.BUILDER_ID.equals(cmd.getBuilderName())) {
                    hasNonJavaBuilder = true;
                    break;
                }
            }
            if (hasNonJavaBuilder || buildSpec.length != 1) {
                org.eclipse.core.resources.ICommand javaBuilder = buildDesc.newCommand();
                javaBuilder.setBuilderName(JavaCore.BUILDER_ID);
                buildDesc.setBuildSpec(new org.eclipse.core.resources.ICommand[]{javaBuilder});
                project.setDescription(buildDesc, monitor);
            }
        }

        IJavaProject javaProject = JavaCore.create(project);

        // Build classpath entries
        List<IClasspathEntry> entries = new ArrayList<>();

        // Source folders
        int sourceCount = 0;
        for (String srcRoot : sourceRoots) {
            IPath srcPath = project.getFullPath().append(srcRoot);
            if (project.getFolder(srcRoot).exists()) {
                entries.add(JavaCore.newSourceEntry(srcPath));
                sourceCount++;
            }
        }

        // JRE container
        entries.add(JavaCore.newContainerEntry(new Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

        // Project references (reactor module dependencies)
        int projectRefCount = 0;
        if (projectReferences != null) {
            for (String refProjectName : projectReferences) {
                IProject refProject = root.getProject(refProjectName);
                if (refProject.exists()) {
                    entries.add(JavaCore.newProjectEntry(refProject.getFullPath()));
                    projectRefCount++;
                }
            }
        }

        // Library JARs (skip non-JAR entries like .pom files from BOM/depchain dependencies)
        int libCount = 0;
        for (String jar : classpathJars) {
            if (!jar.endsWith(".jar")) {
                continue;
            }
            File jarFile = new File(jar);
            if (jarFile.exists()) {
                entries.add(JavaCore.newLibraryEntry(new Path(jar), null, null));
                libCount++;
            }
        }

        IClasspathEntry[] newEntries = entries.toArray(new IClasspathEntry[0]);

        // Skip setRawClasspath if the classpath hasn't changed — avoids a full
        // workspace rebuild (~30s) when the cache provides the same classpath.
        if (alreadyExists && classpathEquals(javaProject.getRawClasspath(), newEntries)) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "unchanged");
            result.put("projectName", projectName);
            result.put("sourceRoots", sourceCount);
            result.put("libraries", libCount);
            result.put("projectReferences", projectRefCount);
            return result;
        }

        // Apply classpath — this triggers IndexManager automatically
        javaProject.setRawClasspath(newEntries, monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("status", alreadyExists ? "updated" : "created");
        result.put("projectName", projectName);
        result.put("sourceRoots", sourceCount);
        result.put("libraries", libCount);
        result.put("projectReferences", projectRefCount);
        return result;
    }

    private static IProject findProjectByLocation(IWorkspaceRoot root, String projectPath) {
        IPath location = new Path(projectPath);
        for (IProject p : root.getProjects()) {
            IPath pLoc = p.getLocation();
            if (pLoc != null && pLoc.equals(location)) {
                return p;
            }
        }
        return null;
    }

    private static boolean classpathEquals(IClasspathEntry[] a, IClasspathEntry[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i].getEntryKind() != b[i].getEntryKind()) {
                return false;
            }
            if (!a[i].getPath().equals(b[i].getPath())) {
                return false;
            }
        }
        return true;
    }
}
