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
package com.ibm.mcp.languagetools.extensions.jdtls.build;

/**
 * Thrown when build support operations (classpath extraction from Maven, Gradle, etc.) fail.
 */
public class BuildSupportException extends Exception {

    public BuildSupportException(String message) {
        super(message);
    }

    public BuildSupportException(String message, Throwable cause) {
        super(message, cause);
    }
}
