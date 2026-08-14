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

import java.nio.file.Path;
import java.util.Map;

import com.ibm.mcp.languagetools.progress.ProgressMonitor;

/**
 * Extracts classpath information from a build tool (Maven, Gradle, etc.)
 * without running a full project import.
 */
public interface ClasspathExtractor {

    /**
     * Returns whether this extractor can handle the given workspace root
     * (e.g., Maven extractor checks for pom.xml, Gradle checks for build.gradle).
     */
    boolean canHandle(Path workspaceRoot);

    /**
     * Extracts classpath information for a specific module within the workspace.
     *
     * @param workspaceRoot the root of the multi-module project
     * @param moduleDir     the directory of the specific module to extract classpath for
     * @param progress      progress monitor for reporting download/resolution progress
     * @return the extracted classpath information
     * @throws ClasspathExtractionException if extraction fails
     */
    ClasspathInfo extract(Path workspaceRoot, Path moduleDir, ProgressMonitor progress)
            throws ClasspathExtractionException;

    /**
     * Removes debug-related JVM options from the process environment to prevent
     * child processes (Maven, Gradle) from attempting to attach a debugger.
     *
     * @param pb              the process builder whose environment to clean
     * @param buildToolOptVar the build tool's options environment variable
     *                        (e.g., {@code "MAVEN_OPTS"} or {@code "GRADLE_OPTS"})
     */
    static void cleanDebugEnvironment(ProcessBuilder pb, String buildToolOptVar) {
        Map<String, String> env = pb.environment();
        env.remove("JAVA_TOOL_OPTIONS");
        env.remove("_JAVA_OPTIONS");
        String opts = env.get(buildToolOptVar);
        if (opts != null) {
            String cleaned = opts
                    .replaceAll("-agentlib:jdwp\\S*", "")
                    .replaceAll("-javaagent:\\S*", "")
                    .trim();
            if (cleaned.isEmpty()) {
                env.remove(buildToolOptVar);
            } else {
                env.put(buildToolOptVar, cleaned);
            }
        }
    }
}
