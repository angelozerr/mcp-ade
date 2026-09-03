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

import java.util.List;

/**
 * A server setting descriptor enriched with the current persisted value,
 * following the JSON Schema format.
 *
 * @param serverId         the server this setting belongs to (e.g. {@code "jdtls"})
 * @param key              setting key (e.g. {@code "maven.buildSupport"})
 * @param title            short human-readable label (JSON Schema {@code title})
 * @param description      tooltip / help text
 * @param type             JSON Schema type: {@code "string"}, {@code "boolean"}, {@code "number"}
 * @param enumValues       allowed values (for string settings with a fixed set of choices)
 * @param enumDescriptions display labels for enum values (ordered array, parallel to {@code enumValues})
 * @param defaultValue     default when not configured
 * @param required         whether this setting must be configured before the server can start
 * @param currentValue     current persisted value (or defaultValue if not set)
 * @param source           where the current value comes from
 */
public record ServerSettingDTO(
        String serverId,
        String key,
        String title,
        String description,
        String type,
        List<String> enumValues,
        List<String> enumDescriptions,
        String defaultValue,
        boolean required,
        String currentValue,
        String source
) {
}
