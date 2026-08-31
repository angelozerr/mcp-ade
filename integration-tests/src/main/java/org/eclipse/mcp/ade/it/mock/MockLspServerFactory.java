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
package org.eclipse.mcp.ade.it.mock;

import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerCreateParams;
import org.eclipse.mcp.ade.lsp.server.LspServerFactory;

/**
 * SPI factory that intercepts the {@code mock-lsp} server ID and creates
 * a {@link MockLspServerBridge} backed by an in-process {@link MockLspLanguageServer}.
 * <p>
 * The {@link MockLspLanguageServer} dynamically checks for registered replay trace data
 * on each request, allowing trace-based tests to reuse the same server instance.
 * <p>
 * Registered via {@code META-INF/services/org.eclipse.mcp.ade.lsp.server.LspServerFactory}.
 */
public class MockLspServerFactory implements LspServerFactory {

    @Override
    public String getServerId() {
        return "mock-lsp";
    }

    @Override
    public LspServer createServer(LspServerCreateParams params) {
        return new MockLspServerBridge(params.getConfig(), params.getWorkspace());
    }
}
