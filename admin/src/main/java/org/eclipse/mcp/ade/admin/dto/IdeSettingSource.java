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
package org.eclipse.mcp.ade.admin.dto;

/**
 * Indicates where an IDE configuration setting value comes from.
 */
public enum IdeSettingSource {

    /**
     * Value comes from the server's default configuration (server.json {@code configuration} section).
     */
    DEFAULT,

    /**
     * Value is overridden by an IDE configuration provider
     * (e.g. {@code .vscode/settings.json}, {@code .bon/settings.json}).
     */
    IDE
}
