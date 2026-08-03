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
package com.ibm.mcp.languagetools.extensions.jdtls.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Static helper for building tool parameter maps.
 */
final class RefactoringHelper {

    private RefactoringHelper() {
    }

    static Map<String, Object> positionParams(String fileUri, int line, int character) {
        Map<String, Object> params = new HashMap<>();
        params.put("uri", fileUri);
        params.put("line", line);
        params.put("character", character);
        return params;
    }

    static Map<String, Object> rangeParams(String fileUri, int startLine, int startCharacter,
                                              int endLine, int endCharacter) {
        Map<String, Object> params = new HashMap<>();
        params.put("uri", fileUri);
        params.put("startLine", startLine);
        params.put("startCharacter", startCharacter);
        params.put("endLine", endLine);
        params.put("endCharacter", endCharacter);
        return params;
    }

    static Map<String, Object> fqnParams(String fullyQualifiedName) {
        Map<String, Object> params = new HashMap<>();
        params.put("fullyQualifiedName", fullyQualifiedName);
        return params;
    }

    static void putApply(Map<String, Object> params, Boolean apply) {
        if (apply != null) {
            params.put("apply", apply);
        }
    }

    static List<String> resolveFileUris(String fileUri, List<String> fileUris) {
        if (fileUris != null && !fileUris.isEmpty()) {
            return fileUris;
        }
        if (fileUri != null) {
            return List.of(fileUri);
        }
        return List.of();
    }

    static void putScope(Map<String, Object> params, String scope, String projectName) {
        if (scope != null) {
            SearchScope searchScope = SearchScope.fromString(scope);
            params.put("scope", searchScope.getValue());
        }
        if (projectName != null) {
            params.put("projectName", projectName);
        }
    }

    static void putMaxResults(Map<String, Object> params, Integer maxResults) {
        if (maxResults != null && maxResults > 0) {
            params.put("maxResults", maxResults);
        }
    }
}
