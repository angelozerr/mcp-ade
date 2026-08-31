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

import java.nio.file.Path;

/**
 * Result of server installation.
 */
public class InstallResult {
    private final Path installDir;
    private final String command;
    private final InstallationStatus status;

    public InstallResult(Path installDir, String command, InstallationStatus status) {
        this.installDir = installDir;
        this.command = command;
        this.status = status;
    }

    public Path getInstallDir() {
        return installDir;
    }

    /**
     * Returns the command to execute the server.
     * This is always returned, even if the server was already installed.
     */
    public String getCommand() {
        return command;
    }

    public InstallationStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return "InstallResult{" +
                "installDir=" + installDir +
                ", command='" + command + '\'' +
                ", status=" + status +
                '}';
    }
}
