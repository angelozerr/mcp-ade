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
package com.ibm.mcp.languagetools.language;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.nio.file.Path;

/**
 * A document filter denotes a document by language, scheme, or glob pattern.
 * Follows the VS Code DocumentFilter API.
 *
 * @see <a href="https://code.visualstudio.com/api/references/document-selector">VS Code DocumentSelector</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DocumentFilter {

    private String language;

    private String scheme;

    private String pattern;

    private transient PathPatternMatcher patternMatcher;

    public DocumentFilter() {
    }

    public DocumentFilter(String language) {
        this.language = language;
    }

    public static DocumentFilter forLanguage(String language) {
        return new DocumentFilter(language);
    }

    public static DocumentFilter forPattern(String pattern) {
        DocumentFilter filter = new DocumentFilter();
        filter.pattern = pattern;
        return filter;
    }

    /**
     * Check if this filter matches the given URI and language.
     */
    public boolean matches(URI uri, String language) {
        return matches(uri, language, null);
    }

    /**
     * Check if this filter matches the given URI and language,
     * using the given basePath for relative pattern scoping.
     *
     * @param uri      the document URI (e.g., URI.create("file:///path/to/file.java"))
     * @param language the language identifier (e.g., "java")
     * @param basePath the workspace base path for relative pattern matching (can be null)
     */
    public boolean matches(URI uri, String language, Path basePath) {
        if (this.language != null && !this.language.equals(language)) {
            return false;
        }

        if (uri == null) {
            return this.scheme == null && this.pattern == null;
        }

        String uriStr = uri.toString();
        if (this.scheme != null && !uriStr.startsWith(this.scheme + ":")) {
            return false;
        }

        if (this.pattern != null) {
            try {
                PathPatternMatcher matcher = getOrCreatePatternMatcher(basePath);
                return matcher.matches(uri);
            } catch (Exception e) {
                return false;
            }
        }

        return true;
    }

    private PathPatternMatcher getOrCreatePatternMatcher(Path basePath) {
        if (basePath == null) {
            if (patternMatcher == null) {
                patternMatcher = PathPatternMatcher.fromPattern(this.pattern, null);
            }
            return patternMatcher;
        }
        return PathPatternMatcher.fromPattern(this.pattern, basePath);
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
        this.patternMatcher = null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("DocumentFilter{");
        boolean first = true;
        if (language != null) {
            sb.append("language='").append(language).append("'");
            first = false;
        }
        if (scheme != null) {
            if (!first) sb.append(", ");
            sb.append("scheme='").append(scheme).append("'");
            first = false;
        }
        if (pattern != null) {
            if (!first) sb.append(", ");
            sb.append("pattern='").append(pattern).append("'");
        }
        sb.append("}");
        return sb.toString();
    }
}
