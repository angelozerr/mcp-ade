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

import org.eclipse.mcp.ade.server.ServerConfigBase;

/**
 * Event fired when a server has been successfully installed.
 */
public class InstallerEvent {

    private final ServerConfigBase config;
    private final InstallResult result;

    public InstallerEvent(ServerConfigBase config, InstallResult result) {
        this.config = config;
        this.result = result;
    }

    public ServerConfigBase getConfig() {
        return config;
    }

    public InstallResult getResult() {
        return result;
    }
}
