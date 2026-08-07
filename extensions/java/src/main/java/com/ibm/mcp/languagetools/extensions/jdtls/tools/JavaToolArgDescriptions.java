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
package com.ibm.mcp.languagetools.extensions.jdtls.tools;

/**
 * Centralized descriptions for Java-specific MCP tool arguments.
 */
public final class JavaToolArgDescriptions {

    private JavaToolArgDescriptions() {
    }

    public static final String URIS =
        "List of URIs for batch operation. When provided, overrides uri and processes all files";

    public static final String SEARCH_SCOPE =
        "Search scope: 'project' for project sources only (faster), 'workspace' for full workspace (default)";

    public static final String PROJECT_NAME =
        "Project name to search in (used when scope='project', defaults to first Java project)";

    public static final String MAX_RESULTS =
        "Maximum number of results to return (0 or omit for unlimited). When truncated, response includes truncated=true";
}
