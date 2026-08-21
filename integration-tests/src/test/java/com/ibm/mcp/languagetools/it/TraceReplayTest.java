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

import com.ibm.mcp.languagetools.extension.ExtensionRegistry;
import com.ibm.mcp.languagetools.it.trace.LspTraceData;
import com.ibm.mcp.languagetools.it.trace.McpTraceData;
import com.ibm.mcp.languagetools.it.trace.ReplayLspServerFactory;
import com.ibm.mcp.languagetools.it.trace.TraceParser;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trace-replay integration test framework.
 * <p>
 * Scans the {@code traces/} resource directories for test cases defined by trace files.
 * Each test case directory must contain:
 * <ul>
 *   <li>{@code mcp.trace} — the MCP tool call request and expected response</li>
 *   <li>One or more {@code <server-id>.trace} files — the LSP server responses to replay</li>
 * </ul>
 * <p>
 * The framework automatically:
 * <ol>
 *   <li>Parses the MCP and LSP trace files</li>
 *   <li>Registers mock LSP servers that replay the recorded LSP responses</li>
 *   <li>Invokes the MCP tool with the recorded arguments</li>
 *   <li>Compares the actual MCP response with the expected one from the trace</li>
 * </ol>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TraceReplayTest {

    @Inject
    ExtensionRegistry extensionRegistry;

    private String testWorkspaceCwd;

    @BeforeAll
    void setUp() throws Exception {
        Awaitility.setDefaultTimeout(Duration.ofSeconds(120));

        URL url = Thread.currentThread().getContextClassLoader().getResource("test-workspace");
        assertNotNull(url, "test-workspace directory not found on classpath");
        testWorkspaceCwd = Path.of(url.toURI()).toString();
    }

    @TestFactory
    Stream<DynamicTest> lspToolsTests() throws Exception {
        return scanTestDirs("traces/lsp-tools");
    }

    @TestFactory
    Stream<DynamicTest> dapToolsTests() throws Exception {
        return scanTestDirs("traces/dap-tools");
    }

    @TestFactory
    Stream<DynamicTest> javaToolsTests() throws Exception {
        return scanTestDirs("traces/java-tools");
    }

    /**
     * Scan the given base path for test directories containing {@code mcp.trace} files,
     * and create a {@link DynamicTest} for each.
     *
     * @param basePath the classpath-relative base path to scan (e.g., "traces/lsp-tools")
     * @return a stream of dynamic tests, or empty if the base path does not exist
     */
    private Stream<DynamicTest> scanTestDirs(String basePath) throws Exception {
        URL baseUrl = Thread.currentThread().getContextClassLoader().getResource(basePath);
        if (baseUrl == null) {
            return Stream.empty();
        }

        Path baseDir = Path.of(baseUrl.toURI());

        // Walk the directory tree to find directories containing mcp.trace.
        // Expected structure: tool-name/language-id/test-case-name/mcp.trace
        return Files.walk(baseDir)
                .filter(Files::isDirectory)
                .filter(dir -> Files.isRegularFile(dir.resolve("mcp.trace")))
                .map(dir -> {
                    String testName = baseDir.relativize(dir).toString().replace('\\', '/');
                    return DynamicTest.dynamicTest(testName, () -> runTraceTest(dir));
                });
    }

    /**
     * Execute a single trace-replay test case.
     *
     * @param testDir the test directory containing mcp.trace and LSP trace files
     */
    private void runTraceTest(Path testDir) throws Exception {
        // 1. Parse MCP trace
        McpTraceData mcpData = TraceParser.parseMcpTrace(testDir.resolve("mcp.trace"));
        assertNotNull(mcpData.toolName(), "MCP trace must contain a tool name");

        // 2. Disable all real LSP servers — only mock-lsp should be active
        List<String> disabledServerIds = new ArrayList<>();
        for (LspServerConfig config : extensionRegistry.getAllLspServerConfigs()) {
            String serverId = config.getServerId();
            if (!"mock-lsp".equals(serverId) && extensionRegistry.isServerEnabled(serverId)) {
                extensionRegistry.disableServer(serverId);
                disabledServerIds.add(serverId);
            }
        }

        // 3. Parse LSP traces and register with the replay factory
        ReplayLspServerFactory.clear();
        List<Path> createdFiles = new ArrayList<>();
        try {
            // 3. Resolve workspace root for ${workspaceRoot} replacement.
            String cwd = testWorkspaceCwd;
            // Build proper file URI prefix for the workspace (forward slashes)
            String cwdUriPrefix = Path.of(cwd).toUri().toString();
            if (cwdUriPrefix.endsWith("/")) {
                cwdUriPrefix = cwdUriPrefix.substring(0, cwdUriPrefix.length() - 1);
            }

            // Parse LSP traces with resolved workspace paths and register.
            // All traces are registered under "mock-lsp" (the server configured
            // in the test workspace), regardless of the original server name
            // in the trace file name.
            List<Path> lspTraceFiles = Files.list(testDir)
                    .filter(p -> p.toString().endsWith(".trace")
                            && !p.getFileName().toString().equals("mcp.trace"))
                    .toList();

            if (lspTraceFiles.isEmpty()) {
                // No LSP traces — register empty data so mock defaults are
                // suppressed and the replay mode returns null for all methods
                ReplayLspServerFactory.register("mock-lsp",
                        new LspTraceData(Map.of(), Map.of()));
            }

            for (Path file : lspTraceFiles) {
                LspTraceData lspData = TraceParser.parseLspTrace(file, cwd, cwdUriPrefix);
                ReplayLspServerFactory.register("mock-lsp", lspData);

                // Create workspace files from didOpen notifications so that
                // ensureFileOpened can read them from disk
                for (var entry : lspData.getOpenDocuments().entrySet()) {
                    try {
                        Path filePath = Path.of(URI.create(entry.getKey()));
                        if (!Files.exists(filePath)) {
                            Files.createDirectories(filePath.getParent());
                            Files.writeString(filePath, entry.getValue());
                            createdFiles.add(filePath);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            // 4. Replace ${workspaceRoot} in MCP arguments
            Map<String, Object> arguments = new HashMap<>(mcpData.arguments());
            String finalCwdUriPrefix = cwdUriPrefix;
            arguments.replaceAll((k, v) -> {
                if (v instanceof String s) {
                    s = s.replace("file:///${workspaceRoot}", finalCwdUriPrefix);
                    s = s.replace("${workspaceRoot}", cwd);
                    return s;
                }
                return v;
            });

            // 5. Resolve ${workspaceRoot} in expected result text
            String expectedText = mcpData.expectedResultText();
            if (expectedText != null) {
                expectedText = expectedText.replace("file:///${workspaceRoot}", finalCwdUriPrefix);
                expectedText = expectedText.replace("${workspaceRoot}", cwd);
            }

            // 6. Call MCP tool and verify the response matches the expected trace
            String finalExpectedText = expectedText;
            try (var client = McpAssured.newConnectedSseClient()) {
                client.when()
                        .toolsCall(mcpData.toolName(), arguments, response -> {
                            assertEquals(mcpData.expectedIsError(), response.isError(),
                                    "isError mismatch for tool " + mcpData.toolName());
                            if (finalExpectedText != null) {
                                String actualText = response.firstContent().asText().text();
                                assertEquals(finalExpectedText, actualText,
                                        "Response text mismatch for tool " + mcpData.toolName());
                            }
                        })
                        .thenAssertResults();
            }
        } finally {
            ReplayLspServerFactory.clear();
            for (Path created : createdFiles) {
                try {
                    Files.deleteIfExists(created);
                } catch (Exception ignored) {
                }
            }
            // Re-enable servers that were disabled for this trace test
            for (String serverId : disabledServerIds) {
                extensionRegistry.enableServer(serverId);
            }
        }
    }
}
