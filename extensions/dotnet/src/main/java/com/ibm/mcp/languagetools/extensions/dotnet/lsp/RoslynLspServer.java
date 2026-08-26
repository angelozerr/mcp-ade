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
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.eclipse.lsp4j.services.LanguageClient;

/**
 * Roslyn language server with custom language client for settings conversion.
 */
public class RoslynLspServer extends LspServer {

    public RoslynLspServer(LspServerConfig config, Workspace workspace) {
        super(config, workspace);
    }

    @Override
    protected LanguageClient createLanguageClient() {
        return new RoslynLanguageClient(this);
    }
}
