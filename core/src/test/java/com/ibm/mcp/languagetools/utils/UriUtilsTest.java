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

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UriUtilsTest {

    // =========================================================================
    // normalizeUri
    // =========================================================================

    // --- percent-encoded colon (Windows drive letter) ---

    @Test
    void normalizeUri_decodesUppercasePercentEncodedColon() {
        assertEquals(
                "file:///C:/Users/test/file.jl",
                UriUtils.normalizeUri("file:///C%3A/Users/test/file.jl"));
    }

    @Test
    void normalizeUri_decodesLowercasePercentEncodedColon() {
        assertEquals(
                "file:///C:/Users/test/file.jl",
                UriUtils.normalizeUri("file:///C%3a/Users/test/file.jl"));
    }

    @Test
    void normalizeUri_decodesColonWithDifferentDriveLetters() {
        assertEquals(
                "file:///D:/projects/app.rs",
                UriUtils.normalizeUri("file:///D%3A/projects/app.rs"));
        assertEquals(
                "file:///E:/workspace/main.go",
                UriUtils.normalizeUri("file:///E%3A/workspace/main.go"));
    }

    @Test
    void normalizeUri_decodesLongWindowsPath() {
        assertEquals(
                "file:///C:/Users/AngeloZerr/git/quarkus/extensions/cache/runtime/src/main/java/io/quarkus/cache/errors.jl",
                UriUtils.normalizeUri("file:///C%3A/Users/AngeloZerr/git/quarkus/extensions/cache/runtime/src/main/java/io/quarkus/cache/errors.jl"));
    }

    // --- percent-encoded space ---

    @Test
    void normalizeUri_decodesSpaceInPath() {
        assertEquals(
                "file:///C:/Users/My Documents/file.jl",
                UriUtils.normalizeUri("file:///C:/Users/My%20Documents/file.jl"));
    }

    @Test
    void normalizeUri_decodesSpaceAndColonTogether() {
        assertEquals(
                "file:///C:/My Projects/hello world.jl",
                UriUtils.normalizeUri("file:///C%3A/My%20Projects/hello%20world.jl"));
    }

    // --- other percent-encoded characters ---

    @Test
    void normalizeUri_decodesParentheses() {
        assertEquals(
                "file:///C:/Program Files (x86)/app.exe",
                UriUtils.normalizeUri("file:///C:/Program%20Files%20%28x86%29/app.exe"));
    }

    @Test
    void normalizeUri_decodesHashInPath() {
        assertEquals(
                "file:///home/user/C#/project.cs",
                UriUtils.normalizeUri("file:///home/user/C%23/project.cs"));
    }

    @Test
    void normalizeUri_decodesMultipleEncodedSegments() {
        assertEquals(
                "file:///C:/a b/c d/e f.txt",
                UriUtils.normalizeUri("file:///C%3A/a%20b/c%20d/e%20f.txt"));
    }

    // --- lowercase drive letter ---

    @Test
    void normalizeUri_uppercasesLowercaseDriveLetter() {
        assertEquals(
                "file:///C:/Users/test/file.java",
                UriUtils.normalizeUri("file:///c:/Users/test/file.java"));
    }

    @Test
    void normalizeUri_uppercasesLowercaseDriveLetterWithEncodedColon() {
        assertEquals(
                "file:///C:/Users/AngeloZerr/file.java",
                UriUtils.normalizeUri("file:///c%3A/Users/AngeloZerr/file.java"));
    }

    @Test
    void normalizeUri_preservesUppercaseDriveLetter() {
        assertEquals(
                "file:///D:/projects/app.java",
                UriUtils.normalizeUri("file:///D:/projects/app.java"));
    }

    // --- already normalized (no-op) ---

    @Test
    void normalizeUri_preservesAlreadyNormalizedWindowsUri() {
        String uri = "file:///C:/Users/test/file.jl";
        assertEquals(uri, UriUtils.normalizeUri(uri));
    }

    @Test
    void normalizeUri_preservesLinuxUri() {
        String uri = "file:///home/user/project/main.py";
        assertEquals(uri, UriUtils.normalizeUri(uri));
    }

    @Test
    void normalizeUri_preservesMacUri() {
        String uri = "file:///Users/dev/workspace/App.swift";
        assertEquals(uri, UriUtils.normalizeUri(uri));
    }

    // --- null and edge cases ---

    @Test
    void normalizeUri_returnsNullForNull() {
        assertNull(UriUtils.normalizeUri(null));
    }

    @Test
    void normalizeUri_preservesEmptyString() {
        assertEquals("", UriUtils.normalizeUri(""));
    }

    @Test
    void normalizeUri_preservesNonFileScheme() {
        assertEquals(
                "https://example.com/path/to/file",
                UriUtils.normalizeUri("https://example.com/path%2Fto%2Ffile"));
    }

    @Test
    void normalizeUri_preservesUriWithoutPercentEncoding() {
        String uri = "https://example.com/path";
        assertSame(uri, UriUtils.normalizeUri(uri));
    }

    @Test
    void normalizeUri_returnsSameInstanceWhenNoPercent() {
        String uri = "file:///home/user/file.py";
        assertSame(uri, UriUtils.normalizeUri(uri));
    }

    @Test
    void normalizeUri_handlesInvalidUriGracefully() {
        String invalid = "not a valid %-uri %ZZ";
        assertEquals(invalid, UriUtils.normalizeUri(invalid));
    }

    // --- preserves URI structure ---

    @Test
    void normalizeUri_preservesTripleSlashStructure() {
        String result = UriUtils.normalizeUri("file:///C%3A/test.txt");
        assertTrue(result.startsWith("file:///"), "Should preserve file:/// prefix, got: " + result);
    }

    @Test
    void normalizeUri_preservesQueryParameters() {
        assertEquals(
                "file:///C:/test.txt?version=2",
                UriUtils.normalizeUri("file:///C%3A/test.txt?version=2"));
    }

    @Test
    void normalizeUri_preservesFragment() {
        assertEquals(
                "file:///C:/test.txt#line10",
                UriUtils.normalizeUri("file:///C%3A/test.txt#line10"));
    }

    // =========================================================================
    // toUri
    // =========================================================================

    @Test
    void toUri_convertsLinuxPath() {
        URI result = UriUtils.toUri("/home/user/file.py");
        assertEquals("file", result.getScheme());
        assertEquals("/home/user/file.py", result.getPath());
    }

    @Test
    void toUri_convertsWindowsPathWithBackslashes() {
        URI result = UriUtils.toUri("C:\\Users\\test\\file.java");
        assertEquals("file", result.getScheme());
        assertEquals("/C:/Users/test/file.java", result.getPath());
    }

    @Test
    void toUri_convertsWindowsPathWithForwardSlashes() {
        URI result = UriUtils.toUri("C:/Users/test/file.java");
        assertEquals("file", result.getScheme());
        assertEquals("/C:/Users/test/file.java", result.getPath());
    }

    @Test
    void toUri_passesThrough_fileUri() {
        URI result = UriUtils.toUri("file:///home/user/file.py");
        assertEquals("file", result.getScheme());
        assertEquals("/home/user/file.py", result.getPath());
    }

    @Test
    void toUri_returnsNullForNull() {
        assertNull(UriUtils.toUri(null));
    }

    @Test
    void toUri_handlesPathWithSpaces() {
        URI result = UriUtils.toUri("/home/my user/file.py");
        assertEquals("file", result.getScheme());
        assertEquals("/home/my user/file.py", result.getPath());
    }

    @Test
    void toUri_prependsSlashForWindowsPath() {
        URI result = UriUtils.toUri("D:\\projects\\app.rs");
        assertTrue(result.getPath().startsWith("/"));
    }

    // =========================================================================
    // cwdToUriPrefix
    // =========================================================================

    @Test
    void cwdToUriPrefix_linuxPath() {
        assertEquals("file:///home/user/project/", UriUtils.cwdToUriPrefix("/home/user/project"));
    }

    @Test
    void cwdToUriPrefix_linuxPathWithTrailingSlash() {
        assertEquals("file:///home/user/project/", UriUtils.cwdToUriPrefix("/home/user/project/"));
    }

    @Test
    void cwdToUriPrefix_windowsPathBackslashes() {
        assertEquals("file:///C:/Users/test/project/", UriUtils.cwdToUriPrefix("C:\\Users\\test\\project"));
    }

    @Test
    void cwdToUriPrefix_windowsPathForwardSlashes() {
        assertEquals("file:///C:/Users/test/project/", UriUtils.cwdToUriPrefix("C:/Users/test/project"));
    }

    @Test
    void cwdToUriPrefix_returnsNullForNull() {
        assertNull(UriUtils.cwdToUriPrefix(null));
    }

    @Test
    void cwdToUriPrefix_addsTrailingSlash() {
        String result = UriUtils.cwdToUriPrefix("/home/user");
        assertTrue(result.endsWith("/"));
    }

    @Test
    void cwdToUriPrefix_doesNotDoubleTrailingSlash() {
        String result = UriUtils.cwdToUriPrefix("/home/user/");
        assertTrue(result.endsWith("/"));
        assertFalse(result.endsWith("//"));
    }

    // =========================================================================
    // compactUri
    // =========================================================================

    @Test
    void compactUri_stripsMatchingCwdPrefix() {
        String cwdUri = "file:///home/user/project/";
        assertEquals("src/Main.java",
                UriUtils.compactUri("file:///home/user/project/src/Main.java", cwdUri));
    }

    @Test
    void compactUri_returnsFullUriWhenCwdDoesNotMatch() {
        String cwdUri = "file:///home/user/project/";
        String uri = "file:///other/path/file.py";
        assertEquals(uri, UriUtils.compactUri(uri, cwdUri));
    }

    @Test
    void compactUri_returnsFullUriWhenCwdIsNull() {
        String uri = "file:///home/user/file.py";
        assertEquals(uri, UriUtils.compactUri(uri, null));
    }

    @Test
    void compactUri_stripsQueryFromNonFileUri() {
        assertEquals("jdt://contents/rt.jar/java.lang/String.class",
                UriUtils.compactUri("jdt://contents/rt.jar/java.lang/String.class?query=value", null));
    }

    @Test
    void compactUri_decodesPercentEncodedNonFileUri() {
        assertEquals("custom://path/hello world",
                UriUtils.compactUri("custom://path/hello%20world", null));
    }

    @Test
    void compactUri_doesNotModifyFileUriWithoutMatchingCwd() {
        String uri = "file:///home/user/file.py";
        assertEquals(uri, UriUtils.compactUri(uri, "file:///other/"));
    }

    // =========================================================================
    // compactUriToMap
    // =========================================================================

    @Test
    void compactUriToMap_returnsFileKeyWithCompactedUri() {
        String cwdUri = "file:///home/user/project/";
        Map<String, String> result = UriUtils.compactUriToMap("file:///home/user/project/src/Main.java", cwdUri);
        assertEquals("src/Main.java", result.get("file"));
    }

    @Test
    void compactUriToMap_returnsFileKeyForUnknownScheme() {
        Map<String, String> result = UriUtils.compactUriToMap("unknown://some/path", null);
        assertEquals("unknown://some/path", result.get("file"));
    }

    @Test
    void compactUriToMap_usesRegisteredCompactor() {
        UriUtils.registerSchemeCompactor("jdt", uri -> Map.of("jdt", uri));
        try {
            Map<String, String> result = UriUtils.compactUriToMap("jdt://contents/rt.jar", null);
            assertEquals("jdt://contents/rt.jar", result.get("jdt"));
        } finally {
            // Clean up: re-register with null-returning compactor to avoid affecting other tests
            UriUtils.registerSchemeCompactor("jdt", uri -> null);
        }
    }

    // =========================================================================
    // stripFileUriPrefix
    // =========================================================================

    @Test
    void stripFileUriPrefix_removesMatchingCwdUri() {
        String cwdUri = "file:///home/user/project/";
        assertEquals("Error in src/Main.java at line 10",
                UriUtils.stripFileUriPrefix("Error in file:///home/user/project/src/Main.java at line 10", cwdUri));
    }

    @Test
    void stripFileUriPrefix_removesMultipleOccurrences() {
        String cwdUri = "file:///home/user/project/";
        String text = "file:///home/user/project/A.java and file:///home/user/project/B.java";
        assertEquals("A.java and B.java", UriUtils.stripFileUriPrefix(text, cwdUri));
    }

    @Test
    void stripFileUriPrefix_returnsOriginalWhenNoMatch() {
        String cwdUri = "file:///home/user/project/";
        String text = "Error in file:///other/path/file.py";
        assertEquals(text, UriUtils.stripFileUriPrefix(text, cwdUri));
    }

    @Test
    void stripFileUriPrefix_returnsOriginalWhenCwdIsNull() {
        String text = "Error in file:///home/user/file.py";
        assertEquals(text, UriUtils.stripFileUriPrefix(text, null));
    }
}
