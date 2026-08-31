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

import java.util.concurrent.CompletableFuture;

/**
 * Generic interface for server installers (LSP and DAP).
 *
 * The installer performs three functions:
 * 1. Check if server is installed
 * 2. Install if needed
 * 3. Calculate the final command to execute
 */
public interface ServerInstaller {

    /**
     * Ensures the server is installed and returns the result.
     *
     * This method:
     * - Checks if already installed (using check task from installer.json)
     * - Installs if needed (using run task from installer.json)
     * - Always returns the command to execute (from configureServer in installer.json)
     *
     * @param context Installation context with progress indicator and variables
     * @return InstallResult with install dir, command, and status
     */
    CompletableFuture<InstallResult> ensureInstalled(InstallerContext context);

    /**
     * Checks whether the server/runtime is already installed without attempting to install it.
     * Runs only the check task from installer.json.
     *
     * @param context Installation context
     * @return InstallResult with ALREADY_INSTALLED if check passes, NOT_INSTALLED if it fails
     */
    CompletableFuture<InstallResult> checkInstalled(InstallerContext context);

    /**
     * Gets the current installation status.
     */
    InstallationStatus getStatus();

    /**
     * Stops the installation if in progress.
     */
    void stop();
}
