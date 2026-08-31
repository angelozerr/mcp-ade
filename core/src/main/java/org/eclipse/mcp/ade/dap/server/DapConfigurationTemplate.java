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

import java.util.Map;

/**
 * A DAP configuration template loaded from /dap/{serverId}/*.json files.
 * <p>
 * Templates provide pre-configured launch/attach configurations for debug adapters.
 * The filename convention is: {@code launch.<name>.json} or {@code attach.<name>.json}
 * </p>
 *
 * @param name  Template name derived from filename (e.g., "launch.program", "attach.port").
 * @param label Display label from JSON "name" field, or fallback to filename.
 * @param body  The complete DAP configuration object (type, request, program, port, etc.).
 */
public record DapConfigurationTemplate(String name, String label, Map<String, Object> body) {
}
