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
package org.eclipse.mcp.ade.extensions.jdtls.dap;

import org.eclipse.mcp.ade.dap.server.DapServer;
import org.eclipse.mcp.ade.dap.server.DapServerCreateParams;
import org.eclipse.mcp.ade.dap.server.DapServerFactory;

/**
 * Factory for creating JavaDebugServer instances.
 * Discovered via Java SPI (ServiceLoader).
 */
public class JavaDebugServerFactory implements DapServerFactory {

    @Override
    public String getServerId() {
        return "java-debug";
    }

    @Override
    public DapServer createServer(DapServerCreateParams params) {
        return new JavaDebugServer(params.getSession(), params.getConfig(), params.getWorkspace());
    }

}
