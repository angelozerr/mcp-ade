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
package org.eclipse.mcp.ade.runtime;

/**
 * Indicates where the runtime binary was actually found.
 */
public enum RuntimeSourceType {

    /**
     * Found on the system PATH.
     */
    PATH,

    /**
     * Provided by the MCP installer.
     */
    INSTALLER,

    /**
     * Source not yet determined.
     */
    UNKNOWN
}
