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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ls.core.internal.managers.IBuildSupport;

import com.google.gson.Gson;

/**
 * IBuildSupport implementation for MCP fast-mode projects.
 *
 * <p>Reads classpath information from a JSON descriptor file written by the
 * MCP server into the JDTLS workspace directory ({@code <dataDir>/mcp-classpath/}).
 * This allows classpath updates to happen during the JDTLS lifecycle rather
 * than through a post-initialization {@code executeCommand} call, avoiding
 * the {@code waitForIndex} bottleneck.</p>
 */
public class McpBuildSupport implements IBuildSupport {

    static final String MCP_CLASSPATH_DIR = "mcp-classpath";

    private static final Gson GSON = new Gson();

    @Override
    public boolean applies(IProject project) {
        Path classpathFile = getClasspathFile(project.getName());
        return classpathFile != null && Files.exists(classpathFile);
    }

    @Override
    public void update(IProject project, boolean force, IProgressMonitor monitor) throws CoreException {
        Path classpathFile = getClasspathFile(project.getName());
        if (classpathFile == null || !Files.exists(classpathFile)) {
            return;
        }

        McpClasspathDescriptor descriptor = readDescriptor(classpathFile);
        if (descriptor == null) {
            return;
        }

        if (!project.isOpen()) {
            project.open(monitor);
        }

        IJavaProject javaProject = JavaCore.create(project);
        List<IClasspathEntry> entries = buildClasspathEntries(project, descriptor);
        IClasspathEntry[] newEntries = entries.toArray(new IClasspathEntry[0]);

        if (!force && classpathEquals(javaProject.getRawClasspath(), newEntries)) {
            return;
        }

        javaProject.setRawClasspath(newEntries, monitor);
    }

    @Override
    public boolean isBuildFile(IResource resource) {
        return false;
    }

    @Override
    public String buildToolName() {
        return "MCP";
    }

    private List<IClasspathEntry> buildClasspathEntries(IProject project, McpClasspathDescriptor descriptor) {
        List<IClasspathEntry> entries = new ArrayList<>();

        // Source folders
        if (descriptor.sourceRoots != null) {
            for (String srcRoot : descriptor.sourceRoots) {
                if (project.getFolder(srcRoot).exists()) {
                    entries.add(JavaCore.newSourceEntry(project.getFullPath().append(srcRoot)));
                }
            }
        }

        // JRE container
        entries.add(JavaCore.newContainerEntry(
                new org.eclipse.core.runtime.Path("org.eclipse.jdt.launching.JRE_CONTAINER")));

        // Project references (reactor modules)
        if (descriptor.projectReferences != null) {
            for (String refName : descriptor.projectReferences) {
                IProject refProject = ResourcesPlugin.getWorkspace().getRoot().getProject(refName);
                if (refProject.exists()) {
                    entries.add(JavaCore.newProjectEntry(refProject.getFullPath()));
                }
            }
        }

        // Library JARs
        if (descriptor.classpathJars != null) {
            for (String jar : descriptor.classpathJars) {
                if (!jar.endsWith(".jar")) {
                    continue;
                }
                File jarFile = new File(jar);
                if (jarFile.exists()) {
                    entries.add(JavaCore.newLibraryEntry(
                            new org.eclipse.core.runtime.Path(jar), null, null));
                }
            }
        }

        return entries;
    }

    static Path getClasspathFile(String projectName) {
        org.eclipse.core.runtime.IPath workspaceRoot =
                ResourcesPlugin.getWorkspace().getRoot().getLocation();
        if (workspaceRoot == null) {
            return null;
        }
        return workspaceRoot.toFile().toPath()
                .resolve(MCP_CLASSPATH_DIR)
                .resolve(projectName + ".json");
    }

    private static McpClasspathDescriptor readDescriptor(Path file) {
        try (Reader reader = Files.newBufferedReader(file)) {
            return GSON.fromJson(reader, McpClasspathDescriptor.class);
        } catch (Exception e) {
            return null;
        }
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

    /**
     * JSON descriptor written by the MCP server to {@code <dataDir>/mcp-classpath/<name>.json}.
     */
    static class McpClasspathDescriptor {
        String projectName;
        String projectPath;
        List<String> sourceRoots;
        List<String> classpathJars;
        List<String> projectReferences;
        boolean disableBuilders;
    }
}
