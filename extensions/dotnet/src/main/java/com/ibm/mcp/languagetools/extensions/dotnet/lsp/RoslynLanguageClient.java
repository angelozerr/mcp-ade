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
package com.ibm.mcp.languagetools.extensions.dotnet.lsp;

import com.ibm.mcp.languagetools.lsp.client.GenericLanguageClient;

/**
 * Language client for Roslyn with settings name conversion.
 * <p>
 * Roslyn sends workspace/configuration requests using server-side option names like
 * {@code csharp|background_analysis.dotnet_compiler_diagnostics_scope}.
 * This client converts them to VS Code client-side names like
 * {@code dotnet.backgroundAnalysis.compilerDiagnosticsScope} so they can be
 * resolved from IDE configuration (.vscode/settings.json) or server.json defaults.
 * <p>
 * Conversion algorithm matches vscode-csharp's {@code convertServerOptionNameToClientConfigurationName}.
 */
public class RoslynLanguageClient extends GenericLanguageClient {

    public RoslynLanguageClient(RoslynLspServer server) {
        super(server);
    }

    @Override
    protected String convertConfigurationSection(String section) {
        if (section == null || section.isEmpty()) {
            return section;
        }

        // Remove language prefix (e.g., "csharp|" from "csharp|background_analysis.dotnet_xxx")
        int pipeIndex = section.indexOf('|');
        String withoutPrefix = pipeIndex >= 0 ? section.substring(pipeIndex + 1) : section;

        String[] parts = withoutPrefix.split("\\.");
        if (parts.length == 0) {
            return section;
        }

        // Last part contains the namespace prefix and feature name
        // e.g., "dotnet_compiler_diagnostics_scope" -> prefix="dotnet", feature="compiler_diagnostics_scope"
        String lastPart = parts[parts.length - 1];
        int firstUnderscore = lastPart.indexOf('_');
        if (firstUnderscore < 0) {
            return section;
        }

        String namespace = lastPart.substring(0, firstUnderscore);
        String featureName = lastPart.substring(firstUnderscore + 1);

        StringBuilder result = new StringBuilder(namespace);

        // Middle parts (groupings) converted to camelCase
        for (int i = 0; i < parts.length - 1; i++) {
            result.append('.');
            result.append(snakeToCamelCase(parts[i]));
        }

        // Feature name converted to camelCase
        result.append('.');
        result.append(snakeToCamelCase(featureName));

        return result.toString();
    }

    private static String snakeToCamelCase(String snake) {
        String[] words = snake.split("_");
        StringBuilder sb = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            if (!words[i].isEmpty()) {
                sb.append(Character.toUpperCase(words[i].charAt(0)));
                sb.append(words[i].substring(1));
            }
        }
        return sb.toString();
    }
}
