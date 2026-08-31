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
package org.eclipse.mcp.ade.language;

import java.util.List;

/**
 * Language definition following VSCode contributes.languages structure.
 * See: https://code.visualstudio.com/api/references/contribution-points#contributes.languages
 */
public record LanguageDefinition(
    String id,
    List<String> extensions,
    List<String> aliases,
    List<String> filenames,
    List<String> filenamePatterns,
    String firstLine
) {
    public LanguageDefinition {
        // Default empty lists if null
        if (extensions == null) extensions = List.of();
        if (aliases == null) aliases = List.of();
        if (filenames == null) filenames = List.of();
        if (filenamePatterns == null) filenamePatterns = List.of();
    }
}
