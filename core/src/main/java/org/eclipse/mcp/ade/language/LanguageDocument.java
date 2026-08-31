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

import java.net.URI;

/**
 * Represents a document with its URI and detected language.
 * The languageId can be null if the language cannot be detected.
 */
public class LanguageDocument {

    private final URI uri;
    private final String languageId;

    public LanguageDocument(URI uri, String languageId) {
        this.uri = uri;
        this.languageId = languageId;
    }

    public URI getUri() {
        return uri;
    }

    public String getLanguageId() {
        return languageId;
    }

    /**
     * Get the URI scheme (e.g., "file", "http").
     * Delegates to the underlying URI.
     */
    public String getScheme() {
        return uri.getScheme();
    }
}
