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

import com.fasterxml.jackson.annotation.JsonValue;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A document selector is a list of {@link DocumentFilter}.
 * Follows the VS Code DocumentSelector API.
 *
 * @see <a href="https://code.visualstudio.com/api/references/document-selector">VS Code DocumentSelector</a>
 */
public class DocumentSelector {

    private final List<DocumentFilter> filters;

    public DocumentSelector(List<DocumentFilter> filters) {
        this.filters = filters != null ? filters : Collections.emptyList();
    }

    @JsonValue
    public List<DocumentFilter> getFilters() {
        return filters;
    }

    /**
     * Check if any filter in this selector matches the given URI and language.
     */
    public boolean matches(URI uri, String language) {
        return matches(uri, language, null);
    }

    /**
     * Check if any filter in this selector matches the given URI and language,
     * scoped to a workspace base path.
     */
    public boolean matches(URI uri, String language, Path basePath) {
        for (DocumentFilter f : filters) {
            if (f.matches(uri, language, basePath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the list of language identifiers declared in this selector's filters.
     */
    public List<String> getLanguages() {
        List<String> languages = new ArrayList<>();
        for (DocumentFilter f : filters) {
            if (f.getLanguage() != null && !languages.contains(f.getLanguage())) {
                languages.add(f.getLanguage());
            }
        }
        return languages;
    }

    /**
     * Returns true if this selector has no filters.
     */
    public boolean isEmpty() {
        return filters.isEmpty();
    }

    @Override
    public String toString() {
        return "DocumentSelector{filters=" + filters + "}";
    }
}
