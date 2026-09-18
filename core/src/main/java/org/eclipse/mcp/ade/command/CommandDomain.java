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
package org.eclipse.mcp.ade.command;

/**
 * Domains for CLI commands. Each domain groups related admin operations.
 */
public enum CommandDomain {

    LSP("lsp"),
    DAP("dap"),
    BSP("bsp"),
    EXTENSION("extension");

    private final String value;

    CommandDomain(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
