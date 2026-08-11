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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DapToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String cwd;
    private String testScriptPath;

    @BeforeAll
    void setUp() throws Exception {
        assumeTrue(isCommandAvailable("python", "--version"),
                "Python not available, skipping DAP tests");
        assumeTrue(isCommandAvailable("python", "-m", "debugpy", "--version"),
                "debugpy not installed, skipping DAP tests");

        Awaitility.setDefaultTimeout(Duration.ofSeconds(120));

        URL url = Thread.currentThread().getContextClassLoader().getResource("test-workspace");
        assertNotNull(url, "test-workspace directory not found on classpath");
        Path workspacePath = Path.of(url.toURI());
        cwd = workspacePath.toString();
        testScriptPath = workspacePath.resolve("test_debug.py").toString();
    }

    private boolean isCommandAvailable(String... command) {
        try {
            Process p = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String getResponseText(ToolResponse response) {
        try {
            return response.firstContent().asText().text();
        } catch (Exception e) {
            if (response.structuredContent() != null) {
                return response.structuredContent().toString();
            }
            return "";
        }
    }

    // ========== Stateless tools ==========

    @Test
    void listDebugAdapters() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("list_debug_adapters", Map.of(
                            "cwd", cwd
                    ), response -> {
                        assertFalse(response.isError());
                        String text = getResponseText(response);
                        assertNotNull(text);
                        assertTrue(text.contains("debugpy"), "Should list debugpy adapter");
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void getDebugTemplates() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("get_debug_templates", Map.of(
                            "debuggerId", "debugpy"
                    ), response -> {
                        assertFalse(response.isError());
                        String text = getResponseText(response);
                        assertNotNull(text);
                        assertTrue(text.contains("launch") || text.contains("Launch"),
                                "Should contain launch template");
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void getDebugStatistics() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("get_debug_statistics", Map.of(), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    @Test
    void listDebugSessionsEmpty() {
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("list_debug_sessions", Map.of(), response -> {
                        assertFalse(response.isError());
                    })
                    .thenAssertResults();
        }
    }

    // ========== Full debug session lifecycle ==========

    @Test
    void debugSessionLifecycle() throws Exception {
        AtomicReference<String> sessionIdRef = new AtomicReference<>();

        // 1. Start debugging with breakpoint at line 8 (z = add(x, y))
        Map<String, Object> startArgs = new HashMap<>();
        startArgs.put("debuggerId", "debugpy");
        startArgs.put("cwd", cwd);
        startArgs.put("configuration", Map.of(
                "request", "launch",
                "program", testScriptPath
        ));
        startArgs.put("breakpoints", List.of(Map.of(
                "file", testScriptPath,
                "line", 8
        )));
        startArgs.put("debugMode", true);

        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("start_debugging", startArgs, response -> {
                        String text = getResponseText(response);
                        assertFalse(response.isError(), "start_debugging failed: " + text);
                        assertTrue(text.contains("sessionId"), "Response should contain sessionId");
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> result = MAPPER.readValue(text, Map.class);
                            sessionIdRef.set((String) result.get("sessionId"));
                        } catch (Exception e) {
                            fail("Failed to parse start_debugging response: " + e.getMessage());
                        }
                    })
                    .thenAssertResults();
        }

        String sessionId = sessionIdRef.get();
        assertNotNull(sessionId, "sessionId should not be null");

        try {
            // 2. Wait for session to reach PAUSED state (breakpoint hit)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> isSessionInState(sessionId, "PAUSED"));

            // 3. Get stack trace — verify we stopped in the script
            try (var client = McpAssured.newConnectedSseClient()) {
                client.when()
                        .toolsCall("get_stack_trace", Map.of(
                                "sessionId", sessionId
                        ), response -> {
                            String text = getResponseText(response);
                            assertFalse(response.isError(), "get_stack_trace failed: " + text);
                            assertTrue(text.contains("frames"), "Should contain stack frames");
                        })
                        .thenAssertResults();
            }

            // 4. Get local variables — x=10, y=20 should be defined
            try (var client = McpAssured.newConnectedSseClient()) {
                client.when()
                        .toolsCall("get_local_variables", Map.of(
                                "sessionId", sessionId
                        ), response -> {
                            String text = getResponseText(response);
                            assertFalse(response.isError(), "get_local_variables failed: " + text);
                        })
                        .thenAssertResults();
            }

            // 5. Evaluate expression
            try (var client = McpAssured.newConnectedSseClient()) {
                client.when()
                        .toolsCall("evaluate_expression", Map.of(
                                "sessionId", sessionId,
                                "expression", "x + y"
                        ), response -> {
                            String text = getResponseText(response);
                            assertFalse(response.isError(), "evaluate_expression failed: " + text);
                            assertTrue(text.contains("30"), "x + y should evaluate to 30");
                        })
                        .thenAssertResults();
            }

            // 6. List threads
            try (var client = McpAssured.newConnectedSseClient()) {
                client.when()
                        .toolsCall("list_threads", Map.of(
                                "sessionId", sessionId
                        ), response -> {
                            String text = getResponseText(response);
                            assertFalse(response.isError(), "list_threads failed: " + text);
                            assertTrue(text.contains("threads"), "Should contain threads");
                        })
                        .thenAssertResults();
            }

            // 7. Step over (from line 8 to line 9)
            try (var client = McpAssured.newConnectedSseClient()) {
                client.when()
                        .toolsCall("step_over", Map.of(
                                "sessionId", sessionId
                        ), response -> {
                            String text = getResponseText(response);
                            assertFalse(response.isError(), "step_over failed: " + text);
                        })
                        .thenAssertResults();
            }

            // 8. Continue execution — program runs to completion
            try (var client = McpAssured.newConnectedSseClient()) {
                client.when()
                        .toolsCall("continue_execution", Map.of(
                                "sessionId", sessionId
                        ), response -> {
                            String text = getResponseText(response);
                            assertFalse(response.isError(), "continue_execution failed: " + text);
                        })
                        .thenAssertResults();
            }

            // 9. Wait for program to terminate
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(500))
                    .until(() -> isSessionInState(sessionId, "TERMINATED"));

            // 10. Get console output — should contain "Result: 30"
            try (var client = McpAssured.newConnectedSseClient()) {
                client.when()
                        .toolsCall("get_console_output", Map.of(
                                "sessionId", sessionId
                        ), response -> {
                            String text = getResponseText(response);
                            assertFalse(response.isError(), "get_console_output failed: " + text);
                            assertTrue(text.contains("Result: 30"),
                                    "Console output should contain 'Result: 30', got: " + text);
                        })
                        .thenAssertResults();
            }

        } finally {
            // Cleanup: close debug session
            try (var client = McpAssured.newConnectedSseClient()) {
                client.when()
                        .toolsCall("close_debug_session", Map.of(
                                "sessionId", sessionId
                        ), response -> {
                        })
                        .thenAssertResults();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean isSessionInState(String sessionId, String expectedState) {
        AtomicBoolean found = new AtomicBoolean(false);
        try (var client = McpAssured.newConnectedSseClient()) {
            client.when()
                    .toolsCall("list_debug_sessions", Map.of(), response -> {
                        String text = getResponseText(response);
                        found.set(text.contains(sessionId) && text.contains(expectedState));
                    })
                    .thenAssertResults();
        } catch (Exception e) {
            return false;
        }
        return found.get();
    }
}
