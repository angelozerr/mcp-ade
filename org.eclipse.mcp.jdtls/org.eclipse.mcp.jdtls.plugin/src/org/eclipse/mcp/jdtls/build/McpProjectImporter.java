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
package org.eclipse.mcp.jdtls.build;

import java.io.File;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ls.core.internal.AbstractProjectImporter;

import com.google.gson.Gson;

/**
 * IProjectImporter implementation for MCP fast-mode projects.
 *
 * <p>Registered with order=10, well before MavenProjectImporter (order=400)
 * and GradleProjectImporter (order=300). When MCP classpath descriptors
 * exist in {@code <dataDir>/mcp-classpath/}, this importer claims the
 * root folder and returns {@code isResolved()=true}, preventing M2E and
 * Gradle importers from running.</p>
 *
 * <p>This importer creates Eclipse IProject resources with Java nature and
 * configures the classpath via {@link McpBuildSupport#update}. Both steps
 * happen during {@code initialize}, so indexing starts immediately.</p>
 *
 * <p>Uses a two-pass approach: first creates all projects, then configures
 * classpath. This ensures project references (reactor module dependencies)
 * resolve correctly regardless of descriptor processing order.</p>
 */
@SuppressWarnings("restriction")
public class McpProjectImporter extends AbstractProjectImporter {

    private static final Gson GSON = new Gson();

    private final McpBuildSupport buildSupport = new McpBuildSupport();

    private List<McpBuildSupport.McpClasspathDescriptor> descriptors;

    @Override
    public boolean applies(IProgressMonitor monitor) throws OperationCanceledException, CoreException {
        Path mcpClasspathDir = getMcpClasspathDir();
        if (mcpClasspathDir == null || !Files.isDirectory(mcpClasspathDir)) {
            return false;
        }
        descriptors = discoverDescriptors();
        return true;
    }

    @Override
    public void importToWorkspace(IProgressMonitor monitor) throws OperationCanceledException, CoreException {
        if (descriptors == null || descriptors.isEmpty()) {
            System.err.println("[McpProjectImporter] No descriptors found, skipping import (first launch)");
            directories = new ArrayList<>();
            if (rootFolder != null) {
                directories.add(rootFolder.toPath());
            }
            return;
        }

        IWorkspaceRoot wsRoot = ResourcesPlugin.getWorkspace().getRoot();
        directories = new ArrayList<>();
        List<IProject> projects = new ArrayList<>();

        // Pass 1: Create/open all projects with Java nature
        for (McpBuildSupport.McpClasspathDescriptor descriptor : descriptors) {
            if (monitor.isCanceled()) {
                throw new OperationCanceledException();
            }
            IProject project = resolveProject(wsRoot, descriptor, monitor);
            projects.add(project);
            if (descriptor.projectPath != null) {
                directories.add(Path.of(descriptor.projectPath));
            }
        }

        // Pass 2: Configure builders and classpath (all projects exist, so refs work)
        for (int i = 0; i < descriptors.size(); i++) {
            if (monitor.isCanceled()) {
                throw new OperationCanceledException();
            }
            configureProject(projects.get(i), descriptors.get(i), monitor);
        }

        System.err.println("[McpProjectImporter] Imported " + descriptors.size() + " projects (with classpath)");
    }

    @Override
    public boolean isResolved(File folder) throws OperationCanceledException, CoreException {
        return true;
    }

    @Override
    public void reset() {
        descriptors = null;
        directories = null;
    }

    private IProject resolveProject(IWorkspaceRoot wsRoot, McpBuildSupport.McpClasspathDescriptor descriptor,
                                    IProgressMonitor monitor) throws CoreException {
        IProject project = wsRoot.getProject(descriptor.projectName);

        if (!project.exists() && descriptor.projectPath != null) {
            IProject existing = findProjectByLocation(wsRoot, descriptor.projectPath);
            if (existing != null) {
                project = existing;
            }
        }

        if (project.exists()) {
            if (!project.isOpen()) {
                project.open(monitor);
            }
        } else {
            createProject(project, descriptor, monitor);
        }

        forceJavaNature(project, monitor);
        return project;
    }

    private void configureProject(IProject project, McpBuildSupport.McpClasspathDescriptor descriptor,
                                  IProgressMonitor monitor) throws CoreException {
        if (descriptor.disableBuilders) {
            disableAllBuilders(project, monitor);
        } else {
            ensureJavaBuilderOnly(project, monitor);
        }
        buildSupport.update(project, false, monitor);
    }

    private void createProject(IProject project, McpBuildSupport.McpClasspathDescriptor descriptor,
                               IProgressMonitor monitor) throws CoreException {
        IProjectDescription description = ResourcesPlugin.getWorkspace().newProjectDescription(descriptor.projectName);

        if (descriptor.projectPath != null) {
            description.setLocationURI(Path.of(descriptor.projectPath).toUri());
        }

        description.setNatureIds(new String[]{JavaCore.NATURE_ID});

        project.create(description, monitor);
        project.open(monitor);

        System.err.println("[McpProjectImporter] Created project: " + descriptor.projectName);
    }

    private void forceJavaNature(IProject project, IProgressMonitor monitor) throws CoreException {
        IProjectDescription desc = project.getDescription();
        String[] natures = desc.getNatureIds();
        if (natures.length != 1 || !JavaCore.NATURE_ID.equals(natures[0])) {
            desc.setNatureIds(new String[]{JavaCore.NATURE_ID});
            project.setDescription(desc, monitor);
        }
    }

    private void disableAllBuilders(IProject project, IProgressMonitor monitor) throws CoreException {
        IProjectDescription desc = project.getDescription();
        if (desc.getBuildSpec().length > 0) {
            desc.setBuildSpec(new org.eclipse.core.resources.ICommand[0]);
            project.setDescription(desc, monitor);
        }
    }

    private void ensureJavaBuilderOnly(IProject project, IProgressMonitor monitor) throws CoreException {
        IProjectDescription desc = project.getDescription();
        org.eclipse.core.resources.ICommand[] buildSpec = desc.getBuildSpec();
        boolean needsUpdate = buildSpec.length != 1;
        if (!needsUpdate) {
            for (var cmd : buildSpec) {
                if (!JavaCore.BUILDER_ID.equals(cmd.getBuilderName())) {
                    needsUpdate = true;
                    break;
                }
            }
        }
        if (needsUpdate) {
            org.eclipse.core.resources.ICommand javaBuilder = desc.newCommand();
            javaBuilder.setBuilderName(JavaCore.BUILDER_ID);
            desc.setBuildSpec(new org.eclipse.core.resources.ICommand[]{javaBuilder});
            project.setDescription(desc, monitor);
        }
    }

    private List<McpBuildSupport.McpClasspathDescriptor> discoverDescriptors() {
        List<McpBuildSupport.McpClasspathDescriptor> result = new ArrayList<>();
        Path mcpClasspathDir = getMcpClasspathDir();
        if (mcpClasspathDir == null || !Files.isDirectory(mcpClasspathDir)) {
            return result;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mcpClasspathDir, "*.json")) {
            for (Path file : stream) {
                McpBuildSupport.McpClasspathDescriptor descriptor = readDescriptor(file);
                if (descriptor != null && descriptor.projectName != null) {
                    result.add(descriptor);
                }
            }
        } catch (Exception e) {
            System.err.println("[McpProjectImporter] Failed to scan mcp-classpath dir: " + e.getMessage());
        }

        return result;
    }

    private static IProject findProjectByLocation(IWorkspaceRoot root, String projectPath) {
        org.eclipse.core.runtime.IPath location = new org.eclipse.core.runtime.Path(projectPath);
        for (IProject p : root.getProjects()) {
            org.eclipse.core.runtime.IPath pLoc = p.getLocation();
            if (pLoc != null && pLoc.equals(location)) {
                return p;
            }
        }
        return null;
    }

    private static Path getMcpClasspathDir() {
        org.eclipse.core.runtime.IPath workspaceRoot =
                ResourcesPlugin.getWorkspace().getRoot().getLocation();
        if (workspaceRoot == null) {
            return null;
        }
        return workspaceRoot.toFile().toPath().resolve(McpBuildSupport.MCP_CLASSPATH_DIR);
    }

    private static McpBuildSupport.McpClasspathDescriptor readDescriptor(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            return GSON.fromJson(reader, McpBuildSupport.McpClasspathDescriptor.class);
        } catch (Exception e) {
            return null;
        }
    }
}
