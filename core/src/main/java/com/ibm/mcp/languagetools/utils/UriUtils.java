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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class UriUtils {

    private static final Map<String, Function<String, Map<String, String>>> schemeCompactors = new ConcurrentHashMap<>();

    private UriUtils() {
    }

    public static void registerSchemeCompactor(String scheme, Function<String, Map<String, String>> compactor) {
        schemeCompactors.put(scheme, compactor);
    }

    public static Map<String, String> compactUriToMap(String uri, String cwdUri) {
        int colonIdx = uri.indexOf(':');
        if (colonIdx > 0) {
            String scheme = uri.substring(0, colonIdx);
            Function<String, Map<String, String>> compactor = schemeCompactors.get(scheme);
            if (compactor != null) {
                Map<String, String> result = compactor.apply(uri);
                if (result != null) {
                    return result;
                }
            }
        }
        return Map.of("file", compactUri(uri, cwdUri));
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
        if (!uri.startsWith("file:")) {
            int queryIdx = uri.indexOf('?');
            if (queryIdx >= 0) {
                uri = uri.substring(0, queryIdx);
            }
            try {
                uri = URLDecoder.decode(uri, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // keep as-is
            }
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
