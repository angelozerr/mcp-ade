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
package com.ibm.mcp.languagetools.lsp.client.capabilities;

import com.ibm.mcp.languagetools.language.PathPatternMatcher;
import org.eclipse.lsp4j.DocumentFilter;

import java.util.Collections;
import java.util.List;

/**
 * Extended document selector with pattern matching support.
 */
public class ExtendedDocumentSelector {

    private final List<ExtendedDocumentFilter> filters;

    /**
     * Extended document filter with lazy pattern matcher creation.
     */
    public class ExtendedDocumentFilter extends DocumentFilter {

        private PathPatternMatcher patternMatcher;

        public ExtendedDocumentFilter(DocumentFilter filter) {
            super.setLanguage(filter.getLanguage());
            super.setScheme(filter.getScheme());
            super.setPattern(filter.getPattern());
        }

        public PathPatternMatcher getPathPattern() {
            if (patternMatcher != null) {
                return patternMatcher;
            }
            var patternEither = super.getPattern();
            if (patternEither == null || !patternEither.isLeft()) {
                // Only support String patterns for now, not RelativePattern
                return null;
            }
            String pattern = patternEither.getLeft();
            if (pattern == null || pattern.isEmpty()) {
                return null;
            }
            patternMatcher = PathPatternMatcher.fromPattern(pattern, null);
            return patternMatcher;
        }
    }

    public ExtendedDocumentSelector(List<DocumentFilter> documentSelector) {
        this.filters = documentSelector != null ?
                documentSelector
                        .stream()
                        .filter(f -> {
                            var pattern = f.getPattern();
                            boolean hasPattern = pattern != null && (pattern.isLeft() ? !pattern.getLeft().isEmpty() : true);
                            String language = f.getLanguage();
                            String scheme = f.getScheme();
                            boolean hasLanguage = language != null && !language.isEmpty();
                            boolean hasScheme = scheme != null && !scheme.isEmpty();
                            return hasLanguage || hasPattern || hasScheme;
                        })
                        .map(f -> new ExtendedDocumentFilter(f))
                        .toList() :
                Collections.emptyList();
    }

    public List<ExtendedDocumentFilter> getFilters() {
        return filters;
    }
}
