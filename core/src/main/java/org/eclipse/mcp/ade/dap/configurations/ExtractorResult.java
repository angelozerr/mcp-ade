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
package org.eclipse.mcp.ade.dap.configurations;

/**
 * Extractor result for network address extraction.
 *
 * @param matches true if the result matches the input from NetworkAddressExtractor
 * @param address the address value retrieved from ${address} and null otherwise
 * @param port    the port value retrieved from ${port} and null otherwise
 */
public record ExtractorResult(
        boolean matches,
        String address,
        String port) {
}
