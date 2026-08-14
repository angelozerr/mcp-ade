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
package com.ibm.mcp.languagetools.extensions.jdtls.classpath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared infrastructure for classpath extractors (Maven, Gradle).
 *
 * <p>Provides common utilities: build-tool executable discovery with wrapper
 * walk-up, Windows {@code cmd /c} command wrapping, standard Java source root
 * detection, and process creation with debug-environment cleanup.</p>
 */
public abstract class AbstractClasspathExtractor implements ClasspathExtractor {

    /**
     * Returns the Unix wrapper script name (e.g., {@code "mvnw"}, {@code "gradlew"}).
     */
    protected abstract String unixWrapperName();

    /**
     * Returns the Windows wrapper script name (e.g., {@code "mvnw.cmd"}, {@code "gradlew.bat"}).
     */
    protected abstract String windowsWrapperName();

    /**
     * Returns the Unix system fallback command (e.g., {@code "mvn"}, {@code "gradle"}).
     */
    protected abstract String unixSystemName();

    /**
     * Returns the Windows system fallback command (e.g., {@code "mvn.cmd"}, {@code "gradle.bat"}).
     */
    protected abstract String windowsSystemName();

    /**
     * Returns the environment variable for build-tool JVM options
     * (e.g., {@code "MAVEN_OPTS"}, {@code "GRADLE_OPTS"}).
     */
    protected abstract String buildToolOptsVar();

    /**
     * Returns {@code true} when running on a Windows operating system.
     */
    protected static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * Locates the build tool executable, walking up the directory tree from
     * {@code projectRoot} to find the wrapper script. Falls back to the system
     * command on PATH if no wrapper is found.
     *
     * @param projectRoot the starting directory to search for the wrapper
     * @return the path to the build tool executable
     */
    protected Path findBuildToolExecutable(Path projectRoot) {
        String wrapperName = isWindows() ? windowsWrapperName() : unixWrapperName();
        Path dir = projectRoot.toAbsolutePath().normalize();
        while (dir != null) {
            Path wrapper = dir.resolve(wrapperName);
            if (Files.isRegularFile(wrapper)) {
                return wrapper;
            }
            dir = dir.getParent();
        }
        return Path.of(isWindows() ? windowsSystemName() : unixSystemName());
    }

    /**
     * Builds a command list from the given executable and arguments, prepending
     * {@code cmd /c} when the executable is a Windows batch/cmd script.
     *
     * @param executable the build tool executable path
     * @param args       command-line arguments
     * @return the complete command list ready for {@link ProcessBuilder}
     */
    protected static List<String> buildCommand(Path executable, String... args) {
        List<String> command = new ArrayList<>();
        String exec = executable.toString();
        if (exec.endsWith(".cmd") || exec.endsWith(".bat")) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(exec);
        command.addAll(List.of(args));
        return command;
    }

    /**
     * Creates a {@link ProcessBuilder} for a build tool invocation with the
     * debug environment cleaned.
     *
     * @param executable the build tool executable
     * @param workingDir the working directory for the process
     * @param args       command-line arguments
     * @return a configured {@link ProcessBuilder} ready to {@code start()}
     */
    protected ProcessBuilder createProcess(Path executable, Path workingDir, String... args) {
        List<String> command = buildCommand(executable, args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        ClasspathExtractor.cleanDebugEnvironment(pb, buildToolOptsVar());
        return pb;
    }

    /**
     * Waits for a process to complete and returns its exit code.
     * Re-interrupts the current thread if the wait is interrupted.
     *
     * @param process the process to wait for
     * @return the exit code
     * @throws ClasspathExtractionException if the thread is interrupted
     */
    protected static int waitForProcess(Process process) throws ClasspathExtractionException {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClasspathExtractionException("Build tool process interrupted");
        }
    }

    /**
     * Detects standard Java source root directories within a module.
     *
     * <p>Checks for the conventional directories:
     * {@code src/main/java}, {@code src/main/resources},
     * {@code src/test/java}, {@code src/test/resources}.
     * Falls back to {@code src} if none of the standard directories exist.</p>
     *
     * @param moduleDir the module directory to inspect
     * @return a list of relative paths to existing source root directories
     */
    protected List<String> detectSourceRoots(Path moduleDir) {
        List<String> roots = new ArrayList<>();
        String[] candidates = {
                "src/main/java",
                "src/main/resources",
                "src/test/java",
                "src/test/resources"
        };
        for (String candidate : candidates) {
            if (Files.isDirectory(moduleDir.resolve(candidate))) {
                roots.add(candidate);
            }
        }
        if (roots.isEmpty() && Files.isDirectory(moduleDir.resolve("src"))) {
            roots.add("src");
        }
        return roots;
    }
}
