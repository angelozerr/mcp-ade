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
 * An LSP configuration setting showing the default value from server.json
 * and the current value from IDE configuration providers
 * (e.g. {@code .vscode/settings.json}, {@code .bon/settings.json}).
 *
 * @param key          setting key (e.g. {@code "java.format.enabled"})
 * @param defaultValue default value from the server's {@code configuration} section
 * @param currentValue current value from IDE configuration (or default if not overridden)
 * @param source       where the current value comes from
 */
public record IdeSettingDTO(
        String key,
        String defaultValue,
        String currentValue,
        IdeSettingSource source
) {
}
