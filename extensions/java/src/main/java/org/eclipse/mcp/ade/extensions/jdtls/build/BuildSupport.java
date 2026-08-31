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
package org.eclipse.mcp.ade.extensions.jdtls.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.mcp.ade.progress.ProgressMonitor;

/**
 * Provides build support for a specific build tool (Maven, Gradle, etc.),
 * extracting classpath information without running a full project import.
 */
public interface BuildSupport {

    /**
     * Returns whether this build support can handle the given workspace root
     * (e.g., Maven checks for pom.xml, Gradle checks for build.gradle).
     */
    boolean canHandle(Path workspaceRoot);

    /**
     * Extracts classpath information for a specific module within the workspace.
     *
     * @param workspaceRoot the root of the multi-module project
     * @param moduleDir     the directory of the specific module to extract classpath for
     * @param progress      progress monitor for reporting download/resolution progress
     * @return the extracted classpath information
     * @throws BuildSupportException if extraction fails
     */
    ClasspathInfo extract(Path workspaceRoot, Path moduleDir, ProgressMonitor progress)
            throws BuildSupportException;

    /**
     * Discovers sub-module names declared in the build file of the given directory.
     *
     * <p>For Maven, reads {@code <modules>} from pom.xml.
     * For Gradle, may read {@code include} from settings.gradle.</p>
     *
     * @param parentDir the directory containing the parent build file
     * @return the list of declared sub-module names (relative directory names),
     *         or an empty list if none are declared or the build file cannot be parsed
     */
    default List<String> discoverSubModules(Path parentDir) {
        return List.of();
    }

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
