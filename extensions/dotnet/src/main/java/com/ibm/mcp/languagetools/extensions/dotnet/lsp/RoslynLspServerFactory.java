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
package com.ibm.mcp.languagetools.extensions.dotnet.lsp;

import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.server.LspServerCreateParams;
import com.ibm.mcp.languagetools.lsp.server.LspServerFactory;

/**
 * Factory for creating Roslyn custom server instances.
 */
public class RoslynLspServerFactory implements LspServerFactory {

    @Override
    public String getServerId() {
        return "roslyn";
    }

    @Override
    public LspServer createServer(LspServerCreateParams params) {
        return new RoslynLspServer(params.getConfig(), params.getWorkspace());
    }
}
