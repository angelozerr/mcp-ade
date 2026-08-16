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
package com.ibm.mcp.languagetools.server;

/**
 * LSP Server status.
 */
public enum ServerStatus {

    NOT_STARTED,

    /**
     * Server is being installed.
     */
    INSTALLING,

    /**
     * Server is being started but not yet initialized.
     */
    STARTING,

    /**
     * Server is running but indexing the workspace (not yet ready to handle requests).
     */
    INDEXING,

    /**
     * Server is running and ready.
     */
    RUNNING,

    /**
     * Server is being stopped.
     */
    STOPPING,

    /**
     * Server is stopped.
     */
    STOPPED,

    /**
     * Server installation failed (download error, extraction error, etc.).
     */
    INSTALL_FAILED,

    /**
     * Server startup failed (process error, initialization error, etc.).
     */
    START_FAILED,

    /**
     * Server is switching from external (IDE) to MCP-managed, or vice-versa.
     */
    SWITCHING,

    /**
     * Connecting to an external LSP server instance (launched by IDE).
     */
    CONNECTING_TO_IDE,

    /**
     * Connected to an external LSP server instance (launched by IDE).
     */
    CONNECTED_TO_IDE,

    ERROR,
    /**
     * Disconnecting from an external LSP server instance.
     */
    DISCONNECTING;

    public boolean isActive() {
        return this == INSTALLING || this == STARTING || this == INDEXING
                || this == RUNNING || this == STOPPING
                || this == SWITCHING || this == CONNECTING_TO_IDE
                || this == CONNECTED_TO_IDE || this == DISCONNECTING;
    }
}
