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
package com.ibm.mcp.languagetools.utils;

import java.net.URI;

public final class UriUtils {

    private UriUtils() {
    }

    public static URI toUri(String path) {
        if (path == null) return null;
        if (path.startsWith("file:")) return URI.create(path);
        String normalized = path.replace("\\", "/");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        return URI.create("file://" + normalized);
    }

    public static String cwdToUriPrefix(String cwd) {
        if (cwd == null) return null;
        String normalized = cwd.replace("\\", "/");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        if (!normalized.endsWith("/")) normalized += "/";
        return "file://" + normalized;
    }

    public static String compactUri(String uri, String cwdUri) {
        if (cwdUri != null && uri.startsWith(cwdUri)) {
            return uri.substring(cwdUri.length());
        }
        return uri;
    }

    public static String stripFileUriPrefix(String text, String cwdUri) {
        if (cwdUri != null && text.contains(cwdUri)) {
            return text.replace(cwdUri, "");
        }
        return text;
    }
}
