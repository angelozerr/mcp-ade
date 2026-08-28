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
package com.ibm.mcp.languagetools.variable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Char-by-char parser for variable expressions in template strings.
 * <p>
 * Recognizes two syntaxes:
 * <ul>
 *   <li>{@code ${name}} — simple variable (e.g. {@code ${serverHome}})</li>
 *   <li>{@code ${prefix:name}} — prefixed variable (e.g. {@code ${vscodeExtension:id}})</li>
 * </ul>
 */
public class VariableParser {

    private VariableParser() {
    }

    /**
     * Parse all variable expressions from the input string.
     *
     * @param input the template string to parse
     * @return list of variable expressions found, in order of appearance
     */
    public static List<VariableExpression> parse(String input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }

        List<VariableExpression> expressions = new ArrayList<>();
        int len = input.length();
        int i = 0;

        while (i < len - 1) {
            if (input.charAt(i) == '$' && input.charAt(i + 1) == '{') {
                int start = i;
                i += 2;

                String prefix = null;
                int colonPos = -1;
                int nameStart = i;

                while (i < len && input.charAt(i) != '}') {
                    if (input.charAt(i) == ':' && colonPos == -1) {
                        colonPos = i;
                    }
                    i++;
                }

                if (i < len) {
                    // Found closing '}'
                    int end = i + 1;
                    String name;
                    if (colonPos != -1) {
                        prefix = input.substring(nameStart, colonPos);
                        name = input.substring(colonPos + 1, i);
                    } else {
                        name = input.substring(nameStart, i);
                    }
                    if (!name.isEmpty()) {
                        expressions.add(new VariableExpression(prefix, name, start, end));
                    }
                    i = end;
                } else {
                    // Unclosed '${' — skip
                    i = start + 1;
                }
            } else {
                i++;
            }
        }

        return expressions;
    }
}
