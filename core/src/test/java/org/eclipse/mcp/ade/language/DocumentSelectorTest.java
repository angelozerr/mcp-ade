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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DocumentSelector} (list of {@link DocumentFilter}).
 */
class DocumentSelectorTest {

    // --- Basic list matching ---

    @Test
    void emptySelector_doesNotMatch() {
        var selector = new DocumentSelector(List.of());
        assertFalse(selector.matches(URI.create("file:///foo.java"), "java"));
    }

    @Test
    void nullFilters_doesNotMatch() {
        var selector = new DocumentSelector(null);
        assertFalse(selector.matches(URI.create("file:///foo.java"), "java"));
    }

    @Test
    void singleFilter_matches() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java")
        ));
        assertTrue(selector.matches(URI.create("file:///foo.java"), "java"));
    }

    @Test
    void singleFilter_doesNotMatch() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java")
        ));
        assertFalse(selector.matches(URI.create("file:///foo.py"), "python"));
    }

    @Test
    void multipleFilters_firstMatches() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java"),
                DocumentFilter.forLanguage("kotlin")
        ));
        assertTrue(selector.matches(URI.create("file:///Foo.java"), "java"));
    }

    @Test
    void multipleFilters_secondMatches() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java"),
                DocumentFilter.forLanguage("kotlin")
        ));
        assertTrue(selector.matches(URI.create("file:///Foo.kt"), "kotlin"));
    }

    @Test
    void multipleFilters_noneMatch() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java"),
                DocumentFilter.forLanguage("kotlin")
        ));
        assertFalse(selector.matches(URI.create("file:///foo.py"), "python"));
    }

    // --- Mixed language and pattern filters ---

    @Test
    void mixedFilters_languageMatches() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java"),
                DocumentFilter.forPattern("**/*.xml")
        ));
        assertTrue(selector.matches(URI.create("file:///Foo.java"), "java"));
    }

    @Test
    void mixedFilters_patternMatches() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java"),
                DocumentFilter.forPattern("**/*.xml")
        ));
        assertTrue(selector.matches(URI.create("file:///pom.xml"), null));
    }

    @Test
    void mixedFilters_noneMatch() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java"),
                DocumentFilter.forPattern("**/*.xml")
        ));
        assertFalse(selector.matches(URI.create("file:///foo.py"), "python"));
    }

    // --- Real server.json patterns ---

    @Test
    void liberty_documentSelector() {
        var filter1 = new DocumentFilter();
        filter1.setLanguage("properties");
        filter1.setPattern("**/bootstrap.properties");

        var filter2 = DocumentFilter.forPattern("**/server.env");

        var selector = new DocumentSelector(List.of(filter1, filter2));

        // bootstrap.properties with language "properties" -> match
        assertTrue(selector.matches(URI.create("file:///project/src/main/resources/bootstrap.properties"), "properties"));

        // server.env without language -> match via pattern
        assertTrue(selector.matches(URI.create("file:///project/liberty/config/server.env"), null));

        // bootstrap.properties with wrong language -> no match
        assertFalse(selector.matches(URI.create("file:///project/src/main/resources/bootstrap.properties"), "yaml"));

        // random file -> no match
        assertFalse(selector.matches(URI.create("file:///project/pom.xml"), null));
    }

    @Test
    void quarkus_documentSelector() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forPattern("**/application.properties"),
                DocumentFilter.forPattern("**/application.yaml"),
                DocumentFilter.forPattern("**/application.yml")
        ));

        assertTrue(selector.matches(URI.create("file:///project/src/main/resources/application.properties"), null));
        assertTrue(selector.matches(URI.create("file:///project/src/main/resources/application.yaml"), null));
        assertTrue(selector.matches(URI.create("file:///project/src/main/resources/application.yml"), null));
        assertFalse(selector.matches(URI.create("file:///project/src/main/resources/bootstrap.properties"), null));
    }

    @Test
    void microprofile_documentSelector() {
        var filter = new DocumentFilter();
        filter.setLanguage("properties");
        filter.setPattern("**/META-INF/microprofile-config.properties");

        var selector = new DocumentSelector(List.of(filter));

        assertTrue(selector.matches(
                URI.create("file:///project/src/main/resources/META-INF/microprofile-config.properties"), "properties"));
        assertFalse(selector.matches(
                URI.create("file:///project/src/main/resources/microprofile-config.properties"), "properties"));
    }

    @Test
    void jdtls_languageOnly() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java")
        ));
        assertTrue(selector.matches(URI.create("file:///src/Foo.java"), "java"));
        assertFalse(selector.matches(URI.create("file:///src/foo.py"), "python"));
    }

    // --- getLanguages ---

    @Test
    void getLanguages_returnsDistinctLanguages() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forLanguage("java"),
                DocumentFilter.forLanguage("kotlin"),
                DocumentFilter.forPattern("**/*.xml")
        ));
        var languages = selector.getLanguages();
        assertEquals(List.of("java", "kotlin"), languages);
    }

    @Test
    void getLanguages_noDuplicates() {
        var filter1 = new DocumentFilter();
        filter1.setLanguage("properties");
        filter1.setPattern("**/bootstrap.properties");

        var filter2 = new DocumentFilter();
        filter2.setLanguage("properties");
        filter2.setPattern("**/application.properties");

        var selector = new DocumentSelector(List.of(filter1, filter2));
        var languages = selector.getLanguages();
        assertEquals(List.of("properties"), languages);
    }

    @Test
    void getLanguages_empty() {
        var selector = new DocumentSelector(List.of(
                DocumentFilter.forPattern("**/*.xml")
        ));
        assertTrue(selector.getLanguages().isEmpty());
    }

    // --- isEmpty ---

    @Test
    void isEmpty_true() {
        assertTrue(new DocumentSelector(List.of()).isEmpty());
        assertTrue(new DocumentSelector(null).isEmpty());
    }

    @Test
    void isEmpty_false() {
        assertFalse(new DocumentSelector(List.of(DocumentFilter.forLanguage("java"))).isEmpty());
    }
}
