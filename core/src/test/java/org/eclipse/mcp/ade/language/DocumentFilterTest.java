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

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DocumentFilter} matching, following VS Code's DocumentFilter semantics.
 *
 * @see <a href="https://code.visualstudio.com/api/references/document-selector">VS Code DocumentSelector</a>
 */
class DocumentFilterTest {

    // --- Language matching ---

    @Test
    void languageOnly_matches() {
        var filter = DocumentFilter.forLanguage("java");
        assertTrue(filter.matches(URI.create("file:///path/to/Foo.java"), "java"));
    }

    @Test
    void languageOnly_doesNotMatch() {
        var filter = DocumentFilter.forLanguage("java");
        assertFalse(filter.matches(URI.create("file:///path/to/foo.py"), "python"));
    }

    @Test
    void noLanguage_matchesAnyLanguage() {
        var filter = DocumentFilter.forPattern("**/*.py");
        assertTrue(filter.matches(URI.create("file:///path/to/foo.py"), "python"));
        assertTrue(filter.matches(URI.create("file:///path/to/foo.py"), "whatever"));
    }

    // --- Scheme matching ---

    @Test
    void scheme_matches() {
        var filter = new DocumentFilter();
        filter.setScheme("file");
        assertTrue(filter.matches(URI.create("file:///path/to/foo.txt"), null));
    }

    @Test
    void scheme_doesNotMatch() {
        var filter = new DocumentFilter();
        filter.setScheme("file");
        assertFalse(filter.matches(URI.create("untitled:Untitled-1"), null));
    }

    // --- Pattern matching: * (single segment wildcard) ---

    @Test
    void star_matchesInSameDirectory() {
        var filter = DocumentFilter.forPattern("**/*.py");
        assertTrue(filter.matches(URI.create("file:///path/to/script.py"), null));
    }

    @Test
    void star_doesNotMatchDifferentExtension() {
        var filter = DocumentFilter.forPattern("**/*.py");
        assertFalse(filter.matches(URI.create("file:///path/to/script.js"), null));
    }

    @Test
    void star_doesNotCrossDirectoryBoundary() {
        var filter = DocumentFilter.forPattern("src/*.py");
        assertFalse(filter.matches(URI.create("file:///project/src/sub/script.py"), null));
    }

    // --- Pattern matching: ** (multi-segment wildcard) ---

    @Test
    void doubleStar_matchesAnyDepth() {
        var filter = DocumentFilter.forPattern("**/*.py");
        assertTrue(filter.matches(URI.create("file:///script.py"), null));
        assertTrue(filter.matches(URI.create("file:///path/script.py"), null));
        assertTrue(filter.matches(URI.create("file:///path/to/deep/script.py"), null));
    }

    @Test
    void doubleStar_matchesExactFilename() {
        var filter = DocumentFilter.forPattern("**/bootstrap.properties");
        assertTrue(filter.matches(URI.create("file:///project/src/main/resources/bootstrap.properties"), null));
    }

    @Test
    void doubleStar_doesNotMatchDifferentFilename() {
        var filter = DocumentFilter.forPattern("**/bootstrap.properties");
        assertFalse(filter.matches(URI.create("file:///project/src/main/resources/other.properties"), null));
    }

    @Test
    void doubleStar_matchesWithIntermediateDirectories() {
        var filter = DocumentFilter.forPattern("**/META-INF/microprofile-config.properties");
        assertTrue(filter.matches(URI.create("file:///project/src/main/resources/META-INF/microprofile-config.properties"), null));
    }

    @Test
    void doubleStar_doesNotMatchWrongPath() {
        var filter = DocumentFilter.forPattern("**/META-INF/microprofile-config.properties");
        assertFalse(filter.matches(URI.create("file:///project/src/main/resources/microprofile-config.properties"), null));
    }

    // --- Pattern matching: ? (single character wildcard) ---

    @Test
    void questionMark_matchesSingleChar() {
        var filter = DocumentFilter.forPattern("**/file?.txt");
        assertTrue(filter.matches(URI.create("file:///path/fileA.txt"), null));
        assertTrue(filter.matches(URI.create("file:///path/file1.txt"), null));
    }

    @Test
    void questionMark_doesNotMatchMultipleChars() {
        var filter = DocumentFilter.forPattern("**/file?.txt");
        assertFalse(filter.matches(URI.create("file:///path/file12.txt"), null));
    }

    @Test
    void questionMark_doesNotMatchZeroChars() {
        var filter = DocumentFilter.forPattern("**/file?.txt");
        assertFalse(filter.matches(URI.create("file:///path/file.txt"), null));
    }

    // --- Pattern matching: {} (brace expansion / alternatives) ---

    @Test
    void braces_matchesFirstAlternative() {
        var filter = DocumentFilter.forPattern("**/*.{ts,js}");
        assertTrue(filter.matches(URI.create("file:///path/app.ts"), null));
    }

    @Test
    void braces_matchesSecondAlternative() {
        var filter = DocumentFilter.forPattern("**/*.{ts,js}");
        assertTrue(filter.matches(URI.create("file:///path/app.js"), null));
    }

    @Test
    void braces_doesNotMatchOtherExtension() {
        var filter = DocumentFilter.forPattern("**/*.{ts,js}");
        assertFalse(filter.matches(URI.create("file:///path/app.py"), null));
    }

    @Test
    void braces_yamlVariants() {
        var filter = DocumentFilter.forPattern("**/application.{yaml,yml}");
        assertTrue(filter.matches(URI.create("file:///project/src/application.yaml"), null));
        assertTrue(filter.matches(URI.create("file:///project/src/application.yml"), null));
        assertFalse(filter.matches(URI.create("file:///project/src/application.json"), null));
    }

    // --- Pattern matching: [] (character classes) ---

    @Test
    void charClass_matchesCharInRange() {
        var filter = DocumentFilter.forPattern("**/file[0-9].txt");
        assertTrue(filter.matches(URI.create("file:///path/file3.txt"), null));
    }

    @Test
    void charClass_doesNotMatchCharOutsideRange() {
        var filter = DocumentFilter.forPattern("**/file[0-9].txt");
        assertFalse(filter.matches(URI.create("file:///path/fileA.txt"), null));
    }

    // --- Null URI edge case ---

    @Test
    void nullUri_noConstraints() {
        var filter = new DocumentFilter();
        assertTrue(filter.matches((URI) null, null));
    }

    @Test
    void nullUri_withPattern() {
        var filter = DocumentFilter.forPattern("**/*.py");
        assertFalse(filter.matches((URI) null, null));
    }

    // --- Dot escaping ---

    @Test
    void dotInPattern_isLiteral() {
        var filter = DocumentFilter.forPattern("**/config.properties");
        assertFalse(filter.matches(URI.create("file:///path/configXproperties"), null));
        assertTrue(filter.matches(URI.create("file:///path/config.properties"), null));
    }

    // --- Real patterns from server.json files ---

    @Test
    void realPattern_serverEnv() {
        var filter = DocumentFilter.forPattern("**/server.env");
        assertTrue(filter.matches(URI.create("file:///project/server.env"), null));
        assertTrue(filter.matches(URI.create("file:///project/liberty/config/server.env"), null));
        assertFalse(filter.matches(URI.create("file:///project/.env"), null));
        assertFalse(filter.matches(URI.create("file:///project/other.env"), null));
    }

    @Test
    void realPattern_applicationYaml() {
        var filter = DocumentFilter.forPattern("**/application.yaml");
        assertTrue(filter.matches(URI.create("file:///project/src/main/resources/application.yaml"), null));
        assertFalse(filter.matches(URI.create("file:///project/src/main/resources/application.yml"), null));
    }

    @Test
    void realPattern_applicationProperties() {
        var filter = DocumentFilter.forPattern("**/application.properties");
        assertTrue(filter.matches(URI.create("file:///project/src/main/resources/application.properties"), null));
        assertFalse(filter.matches(URI.create("file:///project/src/main/resources/bootstrap.properties"), null));
    }

    // --- Language + pattern combined ---

    @Test
    void languageAndPattern_bothMatch() {
        var filter = new DocumentFilter();
        filter.setLanguage("properties");
        filter.setPattern("**/bootstrap.properties");
        assertTrue(filter.matches(URI.create("file:///project/bootstrap.properties"), "properties"));
    }

    @Test
    void languageAndPattern_languageMismatch() {
        var filter = new DocumentFilter();
        filter.setLanguage("properties");
        filter.setPattern("**/bootstrap.properties");
        assertFalse(filter.matches(URI.create("file:///project/bootstrap.properties"), "yaml"));
    }

    @Test
    void languageAndPattern_patternMismatch() {
        var filter = new DocumentFilter();
        filter.setLanguage("properties");
        filter.setPattern("**/bootstrap.properties");
        assertFalse(filter.matches(URI.create("file:///project/other.properties"), "properties"));
    }

    // --- BasePath support ---

    @Test
    void basePath_relativePatternMatchesUnderWorkspace() {
        var filter = DocumentFilter.forPattern("src/**/*.java");
        Path basePath = Paths.get("/project");
        assertTrue(filter.matches(URI.create("file:///project/src/main/Foo.java"), null, basePath));
    }

    @Test
    void basePath_relativePatternDoesNotMatchOutsideWorkspace() {
        var filter = DocumentFilter.forPattern("src/**/*.java");
        Path basePath = Paths.get("/project");
        assertFalse(filter.matches(URI.create("file:///other/src/main/Foo.java"), null, basePath));
    }

    @Test
    void basePath_doubleStarPatternMatchesWithoutBasePath() {
        var filter = DocumentFilter.forPattern("**/*.py");
        assertTrue(filter.matches(URI.create("file:///any/path/script.py"), null, null));
    }

    @Test
    void basePath_singleDirPattern() {
        var filter = DocumentFilter.forPattern("config/*.yml");
        Path basePath = Paths.get("/project");
        assertTrue(filter.matches(URI.create("file:///project/config/app.yml"), null, basePath));
    }

    @Test
    void basePath_singleDirPatternDoesNotCrossDirectories() {
        var filter = DocumentFilter.forPattern("config/*.yml");
        Path basePath = Paths.get("/project");
        assertFalse(filter.matches(URI.create("file:///project/config/sub/app.yml"), null, basePath));
    }

    // --- Liberty server.json real patterns ---

    @Test
    void liberty_bootstrapProperties() {
        var filter = new DocumentFilter();
        filter.setLanguage("properties");
        filter.setPattern("**/bootstrap.properties");
        assertTrue(filter.matches(URI.create("file:///project/src/main/resources/bootstrap.properties"), "properties"));
        assertFalse(filter.matches(URI.create("file:///project/src/main/resources/application.properties"), "properties"));
        assertFalse(filter.matches(URI.create("file:///project/src/main/resources/bootstrap.properties"), "yaml"));
    }

    @Test
    void liberty_serverEnv() {
        var filter = DocumentFilter.forPattern("**/server.env");
        assertTrue(filter.matches(URI.create("file:///project/server.env"), null));
        assertTrue(filter.matches(URI.create("file:///project/src/main/liberty/config/server.env"), null));
        assertFalse(filter.matches(URI.create("file:///project/.env"), null));
    }
}
