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
package com.ibm.mcp.languagetools.variable;

/**
 * A parsed variable expression from a template string.
 *
 * @param prefix the namespace prefix (e.g. "vscodeExtension"), or null for simple variables
 * @param name the variable name (e.g. "serverHome", "jetbrains.intellij-server")
 * @param start the start index in the source string (inclusive)
 * @param end the end index in the source string (exclusive)
 */
public record VariableExpression(String prefix, String name, int start, int end) {
}
