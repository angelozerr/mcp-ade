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
package com.ibm.mcp.languagetools.it;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LspToolsTest {

    private String cwd;
    private String testFileUri;
    private String testErrorFileUri;
    private String testTxtFileUri;

    @BeforeAll
    void setUp() throws Exception {
        Awaitility.setDefaultTimeout(Duration.ofSeconds(120));

        URL url = Thread.currentThread().getContextClassLoader().getResource("test-workspace");
        assertNotNull(url, "test-workspace directory not found on classpath");
        Path workspacePath = Path.of(url.toURI());
        cwd = workspacePath.toString();
        testFileUri = workspacePath.resolve("test.xml").toUri().toString();
        testErrorFileUri = workspacePath.resolve("test-error.xml").toUri().toString();
        testTxtFileUri = workspacePath.resolve("test.txt").toUri().toString();
    }

    @Test
    void getDiagnostics() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("get_diagnostics", Map.of(
                            "cwd", cwd,
                            "uri", testErrorFileUri
                    ), response -> {
                        assertFalse(response.isError());
                        String text = response.firstContent().asText().text();
                        assertNotNull(text);
                        assertFalse(text.isEmpty(), "Expected diagnostics for malformed XML");
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void getDocumentSymbols() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("get_document_symbols", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri
                    ), response -> {
                        assertFalse(response.isError());
                        String text = response.firstContent().asText().text();
                        assertNotNull(text);
                        assertTrue(text.contains("catalog"), "Symbols should contain root element 'catalog'");
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void getCompletions() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("get_completions", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "line", 5,
                            "character", 2,
                            "maxResults", 10
                    ), response -> {
                        String text = response.firstContent().asText().text();
                        assertFalse(response.isError(), "Tool returned error: " + text);
                        assertNotNull(text);
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void formatDocument() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("format_document", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "tabSize", 2,
                            "insertSpaces", true
                    ), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void getHoverInfo() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("get_hover_info", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "line", 3,
                            "character", 5
                    ), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void openAndCloseDocument() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("open_document", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri
                    ), response -> {
                        assertFalse(response.isError());
                        String text = response.firstContent().asText().text();
                        assertTrue(text.contains("Opened"), "Expected 'Opened' in response");
                    })
                    .thenAssertResults();
        }

        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("close_document", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri
                    ), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void rename() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("rename", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "line", 1,
                            "character", 1,
                            "newName", "library"
                    ), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void findReferences() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("find_references", Map.of(
                            "cwd", cwd,
                            "uri", testFileUri,
                            "line", 2,
                            "character", 3
                    ), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void findReferencesBySymbolName() {
        try (var client = McpAssured.newConnectedSseClient()) {
            // First, verify workspace/symbol works for "greet" via the mock server
            client.when()
                    .toolsCall("search_workspace_symbols", Map.of(
                            "cwd", cwd,
                            "query", "greet"
                    ), response -> {
                        assertFalse(response.isError());
                        String text = response.firstContent().asText().text();
                        assertTrue(text.contains("greet"), "Should find 'greet' symbol");
                    })
                    .thenAssertResults();
        }

        try (var client = McpAssured.newConnectedSseClient()) {
            // Now test find_references with symbolName instead of position
            client.when()
                    .toolsCall("find_references", Map.of(
                            "cwd", cwd,
                            "symbolName", "greet"
                    ), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void getHoverInfoBySymbolName() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("get_hover_info", Map.of(
                            "cwd", cwd,
                            "symbolName", "Greeter"
                    ), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void getCodeActions() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("get_code_actions", Map.of(
                            "cwd", cwd,
                            "uri", testErrorFileUri,
                            "line", 2,
                            "character", 3
                    ), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    // --- Mock LSP server tests ---

    @Test
    void searchWorkspaceSymbolsWithMock() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("search_workspace_symbols", Map.of(
                            "cwd", cwd,
                            "query", "Greeter"
                    ), response -> {
                        assertFalse(response.isError());
                        String text = response.firstContent().asText().text();
                        assertNotNull(text);
                        assertTrue(text.contains("Greeter"), "Should find 'Greeter' symbol");
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void searchWorkspaceSymbolsFilterByKind() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("search_workspace_symbols", Map.of(
                            "cwd", cwd,
                            "query", "",
                            "kind", "Method"
                    ), response -> {
                        assertFalse(response.isError());
                        String text = response.firstContent().asText().text();
                        assertNotNull(text);
                        assertTrue(text.contains("greet") || text.contains("main"),
                                "Should contain method symbols");
                        assertFalse(text.contains("\"Greeter\""),
                                "Should not contain class symbols when filtering by Method kind");
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void findReferencesBySymbolNameWithMock() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("find_references", Map.of(
                            "cwd", cwd,
                            "symbolName", "greet"
                    ), response -> {
                        assertFalse(response.isError());
                        String text = response.firstContent().asText().text();
                        assertNotNull(text);
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void getHoverInfoBySymbolNameWithMock() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("get_hover_info", Map.of(
                            "cwd", cwd,
                            "symbolName", "Greeter"
                    ), response -> {
                        assertFalse(response.isError());
                        String text = response.firstContent().asText().text();
                        assertNotNull(text);
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void findReferencesWithEnclosingSymbol() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("find_references", Map.of(
                            "cwd", cwd,
                            "uri", testTxtFileUri,
                            "line", 2,
                            "character", 4,
                            "includeEnclosingSymbol", true
                    ), response -> {
                        assertFalse(response.isError());
                        String text = response.firstContent().asText().text();
                        assertNotNull(text);
                        assertTrue(text.contains("in"), "Response should contain enclosing symbol info ('in' field)");
                    })
                    .thenAssertResults();
        }
    }
}
