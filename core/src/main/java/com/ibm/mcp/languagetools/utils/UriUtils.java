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
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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

    /**
     * Normalize a URI by decoding percent-encoded characters in the path
     * and uppercasing the Windows drive letter for consistent cache lookups.
     * Some language servers encode the colon in Windows drive letters (C%3A instead of C:)
     * or return a lowercase drive letter (c: instead of C:).
     */
    public static String normalizeUri(String uri) {
        if (uri == null) {
            return null;
        }

        String result = uri;

        if (result.indexOf('%') >= 0) {
            try {
                URI parsed = URI.create(result);
                String rawPath = parsed.getRawPath();
                String decodedPath = parsed.getPath();
                if (rawPath != null && decodedPath != null && !rawPath.equals(decodedPath)) {
                    result = result.replace(rawPath, decodedPath);
                }
            } catch (Exception e) {
                // keep as-is
            }
        }

        // Normalize Windows drive letter to uppercase (file:///c:/ -> file:///C:/)
        if (result.length() > 9 && result.startsWith("file:///")) {
            char driveLetter = result.charAt(8);
            if (driveLetter >= 'a' && driveLetter <= 'z' && result.charAt(9) == ':') {
                result = result.substring(0, 8) + Character.toUpperCase(driveLetter) + result.substring(9);
            }
        }

        return result;
    }

    public static URI toUri(String path) {
        if (path == null) return null;
        if (path.startsWith("file:")) return URI.create(path);
        String normalized = path.replace("\\", "/");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        try {
            return new URI("file", "", normalized, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid path: " + path, e);
        }
    }

    /**
     * Return a properly formatted {@code file:///} URI string from a {@link URI}.
     * Java's {@code URI.toString()} may produce {@code file:/C:/path} (single slash)
     * when the authority is null; LSP requires {@code file:///C:/path} (RFC 8089).
     */
    public static String toFileUriString(URI uri) {
        String s = uri.toString();
        if (s.startsWith("file:/") && !s.startsWith("file:///")) {
            s = "file:///" + s.substring(6);
        }
        return s;
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
