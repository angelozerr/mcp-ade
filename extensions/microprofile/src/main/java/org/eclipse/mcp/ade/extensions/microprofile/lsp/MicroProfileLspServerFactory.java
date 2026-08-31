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
package org.eclipse.mcp.ade.extensions.microprofile.lsp;

import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerCreateParams;
import org.eclipse.mcp.ade.lsp.server.LspServerFactory;

public class MicroProfileLspServerFactory implements LspServerFactory {

    @Override
    public String getServerId() {
        return "microprofile";
    }

    @Override
    public LspServer createServer(LspServerCreateParams params) {
        return new MicroProfileLspServer(params.getConfig(), params.getWorkspace());
    }
}
