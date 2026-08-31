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
package org.eclipse.mcp.jdtls;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Handler for a specific MCP delegate command.
 */
public interface ICommandHandler {

    Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception;
}
