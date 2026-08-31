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
package org.eclipse.mcp.ade.admin.dto;

/**
 * An IDE setting read from the workspace's IDE configuration
 * (e.g. {@code .vscode/settings.json}).
 *
 * @param key   setting key (e.g. {@code "java.format.enabled"})
 * @param value current value as string
 */
public record IdeSettingDTO(
        String key,
        String value
) {
}
