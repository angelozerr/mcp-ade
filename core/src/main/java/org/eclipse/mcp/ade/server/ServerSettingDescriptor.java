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
package org.eclipse.mcp.ade.server;

import java.util.List;

/**
 * Describes a configurable setting declared in a server's {@code server.json},
 * following the JSON Schema format used by VS Code.
 *
 * @param key              the setting key (e.g. {@code "maven.buildSupport"})
 * @param title            short human-readable label for the UI (JSON Schema {@code title})
 * @param description      tooltip / help text
 * @param type             JSON Schema type: {@code "string"}, {@code "boolean"}, {@code "number"}
 * @param enumValues       allowed values (for string settings with a fixed set of choices)
 * @param enumDescriptions display labels for enum values (ordered array, parallel to {@code enumValues})
 * @param defaultValue     default value when not configured
 * @param required         whether this setting must be configured before the server can start
 */
public record ServerSettingDescriptor(
        String key,
        String title,
        String description,
        String type,
        List<String> enumValues,
        List<String> enumDescriptions,
        String defaultValue,
        boolean required
) {
}
