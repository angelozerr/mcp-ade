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
package com.ibm.mcp.languagetools.dap.server;

import com.ibm.mcp.languagetools.server.ServerResolverBase;
import com.ibm.mcp.languagetools.tools.ToolException;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Resolves DAP servers by ID with enabled-state checking.
 * Centralizes the logic for finding appropriate debug adapters.
 */
@ApplicationScoped
public class DapServerResolver extends ServerResolverBase {

    /**
     * Get an enabled DAP server config by ID.
     *
     * @param debuggerId the debug adapter ID
     * @return the enabled DAP server config
     * @throws ToolException if the adapter is not found or is disabled
     */
    public DapServerConfig getEnabledDapConfig(String debuggerId) {
        DapServerConfig config = application.getDapServerConfig(debuggerId);
        if (config == null) {
            throw new ToolException("Unknown debug adapter: " + debuggerId + ". Use list_debug_adapters to see available adapters.");
        }
        if (!isEnabled(config)) {
            throw new ToolException("Debug adapter '" + debuggerId + "' is disabled. Enable it in the admin UI before starting a debug session.");
        }
        return config;
    }
}
