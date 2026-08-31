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
package org.eclipse.mcp.ade.installer;

/**
 * Status of server installation.
 */
public enum InstallationStatus {
    /**
     * Server is not installed yet.
     */
    NOT_INSTALLED,

    /**
     * Checking whether the server is installed.
     */
    CHECKING,

    /**
     * Installation is in progress.
     */
    INSTALLING,

    /**
     * Server is successfully installed.
     */
    INSTALLED,

    /**
     * Installation was stopped/cancelled.
     */
    STOPPED,

    /**
     * Installation failed.
     */
    FAILED,

    /**
     * Server was already installed (no action taken).
     */
    ALREADY_INSTALLED
}
