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
package org.eclipse.mcp.ade.dap.server;

import org.eclipse.mcp.ade.server.ServerFactory;

/**
 * Factory for creating DAP server instances.
 * Implementations can provide custom DAP server subclasses for specific debuggers.
 *
 * <p>This follows the same SPI pattern as LspServerFactory.</p>
 *
 * <p>Example: JavaDebugServerFactory creates JavaDebugServer instances that handle
 * Java-specific resolution (classpath, java executable, etc.)</p>
 */
public interface DapServerFactory extends ServerFactory<DapServerConfig, DapServer, DapServerCreateParams> {
}
