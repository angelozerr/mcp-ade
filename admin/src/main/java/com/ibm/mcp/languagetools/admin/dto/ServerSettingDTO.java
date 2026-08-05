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
package com.ibm.mcp.languagetools.admin.dto;

import java.util.List;
import java.util.Map;

/**
 * A server setting descriptor enriched with the current persisted value.
 *
 * @param key          setting key (e.g. {@code "java.import.mode"})
 * @param label        human-readable label
 * @param description  tooltip / help text
 * @param type         {@code "enum"}, {@code "boolean"}, or {@code "string"}
 * @param values       allowed values (for enum type)
 * @param valueLabels  display labels for enum values
 * @param defaultValue default when not configured
 * @param currentValue current persisted value (or defaultValue if not set)
 */
public record ServerSettingDTO(
        String key,
        String label,
        String description,
        String type,
        List<String> values,
        Map<String, String> valueLabels,
        String defaultValue,
        String currentValue,
        String source
) {
}
