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
package org.eclipse.mcp.ade.lsp.client.capabilities;

import org.eclipse.lsp4j.ServerCapabilities;

import static org.eclipse.mcp.ade.lsp.client.capabilities.TextDocumentServerCapabilityRegistry.hasCapability;

/**
 * Capability registry for 'workspace/symbol'.
 */
public class WorkspaceSymbolCapabilityRegistry {

    private ServerCapabilities serverCapabilities;

    public void setServerCapabilities(ServerCapabilities serverCapabilities) {
        this.serverCapabilities = serverCapabilities;
    }

    public boolean isWorkspaceSymbolSupported() {
        return serverCapabilities != null &&
                hasCapability(serverCapabilities.getWorkspaceSymbolProvider());
    }
}
