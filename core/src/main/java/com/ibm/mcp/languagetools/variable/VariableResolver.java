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
 * SPI interface for resolving variable expressions in template strings.
 * <p>
 * Implementations are discovered via {@link java.util.ServiceLoader} and
 * registered in {@code META-INF/services/com.ibm.mcp.languagetools.variable.VariableResolver}.
 * <p>
 * Each resolver handles a specific set of variables. When a variable expression is encountered,
 * the {@link VariableResolverRegistry} tries each resolver in order until one returns a non-null value.
 */
public interface VariableResolver {

    /**
     * Resolve a variable expression.
     *
     * @param expression the parsed variable expression
     * @param context the resolution context (server config, workspace folder, extra variables)
     * @return the resolved value, or {@code null} if this resolver does not handle the expression
     */
    String resolve(VariableExpression expression, VariableContext context);
}
