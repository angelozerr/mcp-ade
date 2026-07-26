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
package com.ibm.mcp.jdtls.tests;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Base class for handler tests. Imports a test Java project into the Eclipse
 * workspace before all tests and cleans up after.
 */
public abstract class AbstractHandlerTest {

    protected static final NullProgressMonitor MONITOR = new NullProgressMonitor();
    protected static IProject project;
    protected static IJavaProject javaProject;
    protected static String projectUri;

    @BeforeAll
    static void setUpWorkspace() throws Exception {
        IWorkspace workspace = ResourcesPlugin.getWorkspace();

        // Find the test-projects directory relative to the bundle
        Path testProjectPath = findTestProject();
        if (testProjectPath == null) {
            throw new IllegalStateException("Cannot find test-projects/simple-java directory");
        }

        // Copy test project to workspace
        Path workspaceRoot = workspace.getRoot().getLocation().toFile().toPath();
        Path targetPath = workspaceRoot.resolve("simple-java");
        if (Files.exists(targetPath)) {
            deleteRecursively(targetPath);
        }
        copyDirectory(testProjectPath, targetPath);

        // Import project
        Path projectDescriptionPath = targetPath.resolve(".project");
        IProjectDescription description = workspace.loadProjectDescription(
                new org.eclipse.core.runtime.Path(projectDescriptionPath.toString()));
        description.setLocation(new org.eclipse.core.runtime.Path(targetPath.toString()));

        project = workspace.getRoot().getProject(description.getName());
        if (!project.exists()) {
            project.create(description, MONITOR);
        }
        project.open(MONITOR);

        javaProject = JavaCore.create(project);

        if (JavaManipulation.getPreferenceNodeId() == null) {
            JavaManipulation.setPreferenceNodeId("org.eclipse.jdt.ui");
        }
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(JavaManipulation.getPreferenceNodeId());
        prefs.put("org.eclipse.jdt.ui.importorder", "java;javax;org;com");
        prefs.put("org.eclipse.jdt.ui.ondemandthreshold", "99");
        prefs.put("org.eclipse.jdt.ui.staticondemandthreshold", "99");
        prefs.flush();

        // Wait for the Java model to build
        waitForBuild();

        // Compute base URI for files
        projectUri = project.getLocation().toFile().toURI().toString();
        if (projectUri.endsWith("/")) {
            projectUri = projectUri.substring(0, projectUri.length() - 1);
        }
    }

    @AfterAll
    static void tearDownWorkspace() throws Exception {
        if (project != null && project.exists()) {
            project.delete(true, true, MONITOR);
        }
    }

    protected static String fileUri(String relativePath) {
        return projectUri + "/" + relativePath;
    }

    protected static List<Object> args(Map<String, Object> params) {
        return List.of(params);
    }

    protected static Map<String, Object> params(String uri, int line, int character) {
        Map<String, Object> params = new HashMap<>();
        params.put("uri", uri);
        params.put("line", line);
        params.put("character", character);
        return params;
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> asMap(Object result) {
        return (Map<String, Object>) result;
    }

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> asList(Object obj) {
        return (List<Map<String, Object>>) obj;
    }

    private static void waitForBuild() throws Exception {
        // Wait for auto-build to complete
        boolean wasInterrupted = false;
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                ResourcesPlugin.getWorkspace().getRoot().getProject("simple-java")
                        .refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, MONITOR);
                org.eclipse.core.resources.IncrementalProjectBuilder.class.getName(); // ensure class loaded
                ResourcesPlugin.getWorkspace().build(
                        org.eclipse.core.resources.IncrementalProjectBuilder.FULL_BUILD, MONITOR);
                break;
            } catch (Exception e) {
                if (i == maxAttempts - 1) {
                    throw e;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    wasInterrupted = true;
                }
            }
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path findTestProject() {
        // Try relative paths from various locations
        String[] candidates = {
                "../test-projects/simple-java",
                "../../test-projects/simple-java",
                "test-projects/simple-java"
        };
        for (String candidate : candidates) {
            Path path = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.exists(path.resolve(".project"))) {
                return path;
            }
        }
        // Try from user.dir
        Path userDir = Path.of(System.getProperty("user.dir", "."));
        Path fromUserDir = userDir.resolve("test-projects/simple-java");
        if (Files.exists(fromUserDir.resolve(".project"))) {
            return fromUserDir;
        }
        return null;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Skip bin directory
                if (dir.getFileName().toString().equals("bin")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
