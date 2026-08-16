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
package com.ibm.mcp.languagetools.bsp.server;

import com.ibm.mcp.languagetools.server.ServerFactory;

/**
 * SPI interface for creating custom BSP server implementations.
 * Extensions implement this interface and register via ServiceLoader.
 */
public interface BspServerFactory extends ServerFactory<BspServerConfig, BspServer, BspServerCreateParams> {
}
