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

/**
 * Thrown when classpath extraction from a build tool (Maven or Gradle) fails.
 *
 * <p>Common causes include:
 * <ul>
 *   <li>Missing {@code pom.xml} or {@code build.gradle} in the target directory</li>
 *   <li>{@code mvn dependency:build-classpath} failing (e.g., unresolvable SNAPSHOT dependencies)</li>
 *   <li>Gradle init script task failing</li>
 *   <li>I/O errors reading POM files or accessing the local repository</li>
 *   <li>Maven/Gradle process interrupted</li>
 * </ul>
 *
 * <p>This is a checked exception because callers typically fall back to
 * an alternative extraction strategy or report a degraded classpath.</p>
 */
public class ClasspathExtractionException extends Exception {

    /**
     * @param message a description of the extraction failure
     */
    public ClasspathExtractionException(String message) {
        super(message);
    }

    /**
     * @param message a description of the extraction failure
     * @param cause   the underlying exception (e.g., {@link java.io.IOException})
     */
    public ClasspathExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
