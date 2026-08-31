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
package org.eclipse.mcp.ade.it;

import org.eclipse.mcp.ade.utils.UriUtils;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SymbolEditToolsTest {

    private String cwd;
    private String testFileUri;
    private Path testFilePath;
    private String originalContent;

    @BeforeAll
    void setUp() throws Exception {
        Awaitility.setDefaultTimeout(Duration.ofSeconds(120));

        URL url = Thread.currentThread().getContextClassLoader().getResource("test-workspace");
        assertNotNull(url, "test-workspace directory not found on classpath");
        Path workspacePath = Path.of(url.toURI());
        cwd = workspacePath.toString();
        testFilePath = workspacePath.resolve("test-symbol-edit.xml");
        testFileUri = UriUtils.toFileUriString(testFilePath.toUri());
        originalContent = Files.readString(testFilePath);
    }

    @AfterEach
    void restoreTestFile() throws Exception {
        Files.writeString(testFilePath, originalContent);
    }

    // --- insert_before_symbol ---

    @Test
    void insertBeforeSymbol_preview() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("insert_before_symbol", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "namePath", "catalog/book[0]",
                            "body", "  <!-- New book section -->\n"
                    ), response -> {
                        String text = response.firstContent().asText().text();
                        assertFalse(response.isError(), "Tool returned error: " + text);
                        assertTrue(text.contains("\"applied\":false"), "Expected applied=false: " + text);
                        assertTrue(text.contains("\"edits\""), "Expected edits in response: " + text);
                    })
                    .thenAssertResults();
        }
        // File should NOT be modified in preview mode
        assertDoesNotThrow(() -> {
            String content = Files.readString(testFilePath);
            assertEquals(originalContent, content, "File should not be modified in preview mode");
        });
    }

    @Test
    void insertBeforeSymbol_apply() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("insert_before_symbol", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "namePath", "catalog/book[0]",
                            "body", "  <!-- New book section -->\n",
                            "apply", true
                    ), response -> {
                        String text = response.firstContent().asText().text();
                        assertFalse(response.isError(), "Tool returned error: " + text);
                        assertTrue(text.contains("\"applied\":true"), "Expected applied=true: " + text);
                    })
                    .thenAssertResults();
        }
        assertDoesNotThrow(() -> {
            String content = Files.readString(testFilePath);
            assertNotEquals(originalContent, content, "File should be modified after apply");
            assertTrue(content.contains("<!-- New book section -->"), "Expected inserted content");
        });
    }

    // --- insert_after_symbol ---

    @Test
    void insertAfterSymbol_preview() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("insert_after_symbol", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "namePath", "catalog/book[1]",
                            "body", "  <!-- End of books -->\n"
                    ), response -> {
                        String text = response.firstContent().asText().text();
                        assertFalse(response.isError(), "Tool returned error: " + text);
                        assertTrue(text.contains("\"applied\":false"), "Expected applied=false: " + text);
                        assertTrue(text.contains("\"edits\""), "Expected edits in response: " + text);
                    })
                    .thenAssertResults();
        }
        assertDoesNotThrow(() -> {
            String content = Files.readString(testFilePath);
            assertEquals(originalContent, content, "File should not be modified in preview mode");
        });
    }

    @Test
    void insertAfterSymbol_apply() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("insert_after_symbol", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "namePath", "catalog/book[1]",
                            "body", "  <!-- End of books -->\n",
                            "apply", true
                    ), response -> {
                        String text = response.firstContent().asText().text();
                        assertFalse(response.isError(), "Tool returned error: " + text);
                        assertTrue(text.contains("\"applied\":true"), "Expected applied=true: " + text);
                    })
                    .thenAssertResults();
        }
        assertDoesNotThrow(() -> {
            String content = Files.readString(testFilePath);
            assertNotEquals(originalContent, content, "File should be modified after apply");
            assertTrue(content.contains("<!-- End of books -->"), "Expected inserted content");
        });
    }

    // --- replace_symbol_body ---

    @Test
    void replaceSymbolBody_preview() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("replace_symbol_body", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "namePath", "catalog/book[0]",
                            "body", "<book id=\"1\">\n    <title>Updated Title</title>\n    <author>John Doe</author>\n  </book>"
                    ), response -> {
                        String text = response.firstContent().asText().text();
                        assertFalse(response.isError(), "Tool returned error: " + text);
                        assertTrue(text.contains("\"applied\":false"), "Expected applied=false: " + text);
                        assertTrue(text.contains("\"edits\""), "Expected edits in response: " + text);
                    })
                    .thenAssertResults();
        }
        assertDoesNotThrow(() -> {
            String content = Files.readString(testFilePath);
            assertEquals(originalContent, content, "File should not be modified in preview mode");
        });
    }

    @Test
    void replaceSymbolBody_apply() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("replace_symbol_body", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "namePath", "catalog/book[0]",
                            "body", "<book id=\"1\">\n    <title>Updated Title</title>\n    <author>John Doe</author>\n  </book>",
                            "apply", true
                    ), response -> {
                        String text = response.firstContent().asText().text();
                        assertFalse(response.isError(), "Tool returned error: " + text);
                        assertTrue(text.contains("\"applied\":true"), "Expected applied=true: " + text);
                    })
                    .thenAssertResults();
        }
        assertDoesNotThrow(() -> {
            String content = Files.readString(testFilePath);
            assertNotEquals(originalContent, content, "File should be modified after apply");
            assertTrue(content.contains("Updated Title"), "Expected replaced content");
        });
    }
}
