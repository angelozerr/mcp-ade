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
}
