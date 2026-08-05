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
package com.ibm.mcp.languagetools.configuration;

/**
 * Indicates where a resolved configuration value comes from.
 */
public enum ConfigurationSource {

    /**
     * Value is overridden at workspace level ({workspace}/.mcp-languagetools/settings.json).
     */
    WORKSPACE,

    /**
     * Value comes from application configuration (~/.mcp-languagetools/settings.json).
     */
    APPLICATION,

    /**
     * No value found in workspace or global — using the default.
     */
    DEFAULT
}
