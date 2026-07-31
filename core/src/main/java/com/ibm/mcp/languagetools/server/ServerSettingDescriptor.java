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
package com.ibm.mcp.languagetools.server;

import java.util.List;
import java.util.Map;

/**
 * Describes a configurable setting declared in a server's {@code server.json}.
 *
 * @param key         the setting key (e.g. {@code "java.import.mode"})
 * @param label       human-readable label for the UI
 * @param description tooltip / help text
 * @param type        setting type: {@code "enum"}, {@code "boolean"}, or {@code "string"}
 * @param values      allowed values (for {@code "enum"} type)
 * @param valueLabels display labels for enum values (key = value, value = label)
 * @param defaultValue default value when not configured
 */
public record ServerSettingDescriptor(
        String key,
        String label,
        String description,
        String type,
        List<String> values,
        Map<String, String> valueLabels,
        String defaultValue
) {
}
