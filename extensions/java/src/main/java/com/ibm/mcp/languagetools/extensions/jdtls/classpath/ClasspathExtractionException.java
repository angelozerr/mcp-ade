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
 */
public class ClasspathExtractionException extends Exception {

    public ClasspathExtractionException(String message) {
        super(message);
    }

    public ClasspathExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
