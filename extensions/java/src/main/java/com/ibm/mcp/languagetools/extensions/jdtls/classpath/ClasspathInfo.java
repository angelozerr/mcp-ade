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

import java.util.List;

/**
 * Holds the extracted classpath information for a single module/project.
 *
 * @param moduleName        the Maven artifactId or Gradle project name
 * @param projectPath       absolute path to the module directory on disk
 * @param sourceRoots       relative paths to source folders (e.g., "src/main/java")
 * @param classpathJars     absolute paths to external dependency JARs
 * @param reactorModuleDeps reactor module dependencies (to be set up as source projects)
 */
public record ClasspathInfo(
        String moduleName,
        String projectPath,
        List<String> sourceRoots,
        List<String> classpathJars,
        List<ReactorModule> reactorModuleDeps) {

    /**
     * A reactor module dependency that should be set up as a JDT source project
     * instead of a JAR reference.
     *
     * @param artifactId the Maven artifactId
     * @param modulePath absolute path to the module directory
     */
    public record ReactorModule(String artifactId, String modulePath) {
    }
}
