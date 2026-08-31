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
package org.eclipse.mcp.ade.it.trace;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LSP and MCP trace files into structured data for replay-based testing.
 * <p>
 * Supports both LSP trace format (with {@code Params:}/{@code Result:} prefixes,
 * as produced by VS Code / lsp4ij) and MCP trace format (raw JSON-RPC body).
 */
public class TraceParser {

    /**
     * Pattern matching LSP/MCP trace header lines.
     * <p>
     * Examples:
     * <ul>
     *   <li>{@code [Trace - 19:13:52] Sending request 'initialize - (1)'.}</li>
     *   <li>{@code [Trace - 19:13:52] Received response 'initialize - (1)' in 500ms.}</li>
     *   <li>{@code [Trace - 19:13:52] Sending notification 'initialized'}</li>
     *   <li>{@code [Trace - 19:13:52] Received notification 'textDocument/publishDiagnostics'}</li>
     * </ul>
     */
    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "\\[Trace - [^]]+] (Sending|Received) (request|response|notification) '([^']+)'(?:\\s+in \\d+ms)?\\.?"
    );

    /**
     * Pattern to extract method and optional ID from the method part of the header.
     * Matches {@code "initialize - (1)"} or just {@code "initialized"}.
     */
    private static final Pattern METHOD_ID_PATTERN = Pattern.compile(
            "^(.+?)(?:\\s+-\\s+\\((\\d+)\\))?$"
    );

    private static final Gson GSON = new Gson();

    private TraceParser() {
    }

    // ---- Public API ----

    /**
     * Parse an MCP trace file into structured MCP trace data.
     *
     * @param path the path to the MCP trace file
     * @return the parsed MCP trace data
     * @throws IOException if the file cannot be read
     */
    public static McpTraceData parseMcpTrace(Path path) throws IOException {
        List<TraceEntry> entries = parseEntries(path, false);

        String toolName = null;
        Map<String, Object> arguments = null;
        String expectedResultText = null;
        boolean expectedIsError = false;

        for (TraceEntry entry : entries) {
            if ("Sending".equals(entry.direction()) && "request".equals(entry.type())
                    && entry.method() != null && entry.method().startsWith("tools/call")) {
                // Parse the JSON-RPC request body to extract tool name and arguments
                JsonObject body = GSON.fromJson(entry.body(), JsonObject.class);
                JsonObject params = body.getAsJsonObject("params");
                toolName = params.get("name").getAsString();
                arguments = GSON.fromJson(
                        params.getAsJsonObject("arguments"),
                        new TypeToken<Map<String, Object>>() {
                        }.getType()
                );
            } else if ("Received".equals(entry.direction()) && "response".equals(entry.type())
                    && entry.method() != null && entry.method().startsWith("tools/call")) {
                // Parse the JSON-RPC response body to extract expected result
                JsonObject body = GSON.fromJson(entry.body(), JsonObject.class);
                JsonObject result = body.getAsJsonObject("result");
                if (result != null) {
                    expectedIsError = result.has("isError") && result.get("isError").getAsBoolean();
                    if (result.has("content") && result.get("content").isJsonArray()) {
                        JsonElement firstContent = result.getAsJsonArray("content").get(0);
                        if (firstContent.isJsonObject() && firstContent.getAsJsonObject().has("text")) {
                            expectedResultText = firstContent.getAsJsonObject().get("text").getAsString();
                        }
                    }
                }
            }
        }

        return new McpTraceData(toolName, arguments != null ? arguments : Map.of(),
                expectedResultText, expectedIsError);
    }

    /**
     * Parse an LSP trace file into structured LSP trace data.
     * <p>
     * Only "Received response" entries are stored, as those are needed for replaying
     * server responses. Multiple responses for the same method are queued in order.
     *
     * @param path the path to the LSP trace file
     * @return the parsed LSP trace data
     * @throws IOException if the file cannot be read
     */
    public static LspTraceData parseLspTrace(Path path) throws IOException {
        return parseLspTrace(path, null, null);
    }

    public static LspTraceData parseLspTrace(Path path, String workspacePath, String workspaceUriPrefix) throws IOException {
        List<TraceEntry> entries = parseEntries(path, true);
        Map<String, Queue<String>> responsesByMethod = new LinkedHashMap<>();
        Map<String, String> openDocuments = new LinkedHashMap<>();

        for (TraceEntry entry : entries) {
            if ("Received".equals(entry.direction()) && "response".equals(entry.type())) {
                String body = entry.body();
                if (workspaceUriPrefix != null) {
                    body = body.replace("file:///${workspaceRoot}", workspaceUriPrefix);
                }
                if (workspacePath != null) {
                    body = body.replace("${workspaceRoot}", workspacePath);
                }
                responsesByMethod
                        .computeIfAbsent(entry.method(), k -> new LinkedList<>())
                        .add(body);
            } else if ("Sending".equals(entry.direction()) && "notification".equals(entry.type())
                    && "textDocument/didOpen".equals(entry.method())) {
                String body = entry.body();
                if (workspaceUriPrefix != null) {
                    body = body.replace("file:///${workspaceRoot}", workspaceUriPrefix);
                }
                if (workspacePath != null) {
                    body = body.replace("${workspaceRoot}", workspacePath);
                }
                JsonObject params = GSON.fromJson(body, JsonObject.class);
                if (params != null && params.has("textDocument")) {
                    JsonObject td = params.getAsJsonObject("textDocument");
                    String uri = td.has("uri") ? td.get("uri").getAsString() : null;
                    String text = td.has("text") ? td.get("text").getAsString() : "";
                    if (uri != null) {
                        openDocuments.put(uri, text);
                    }
                }
            }
        }

        return new LspTraceData(responsesByMethod, openDocuments);
    }

    // ---- Parsing internals ----

    /**
     * Parse a trace file into a list of {@link TraceEntry} objects.
     *
     * @param path    the path to the trace file
     * @param isLsp   true for LSP format (with Params:/Result: prefixes), false for MCP format
     * @return the list of parsed trace entries
     * @throws IOException if the file cannot be read
     */
    private static List<TraceEntry> parseEntries(Path path, boolean isLsp) throws IOException {
        List<String> lines = Files.readAllLines(path);
        List<TraceEntry> entries = new ArrayList<>();

        String currentDirection = null;
        String currentType = null;
        String currentMethod = null;
        String currentId = null;
        StringBuilder bodyBuilder = null;

        for (String line : lines) {
            Matcher headerMatcher = HEADER_PATTERN.matcher(line);
            if (headerMatcher.matches()) {
                // Flush previous entry if any
                if (bodyBuilder != null && currentDirection != null) {
                    entries.add(new TraceEntry(currentDirection, currentType, currentMethod,
                            currentId, bodyBuilder.toString().trim()));
                }

                // Parse new header
                currentDirection = headerMatcher.group(1);
                currentType = headerMatcher.group(2);
                String methodPart = headerMatcher.group(3);

                Matcher methodMatcher = METHOD_ID_PATTERN.matcher(methodPart);
                if (methodMatcher.matches()) {
                    currentMethod = methodMatcher.group(1).trim();
                    currentId = methodMatcher.group(2); // may be null for notifications
                } else {
                    currentMethod = methodPart.trim();
                    currentId = null;
                }

                bodyBuilder = new StringBuilder();
            } else if (bodyBuilder != null) {
                // Body line — strip Params:/Result: prefix if LSP format
                String bodyLine = line;
                if (isLsp && bodyBuilder.isEmpty()) {
                    if (bodyLine.startsWith("Params:")) {
                        bodyLine = bodyLine.substring("Params:".length());
                    } else if (bodyLine.startsWith("Result:")) {
                        bodyLine = bodyLine.substring("Result:".length());
                    }
                }
                if (!bodyBuilder.isEmpty()) {
                    bodyBuilder.append("\n");
                }
                bodyBuilder.append(bodyLine);
            }
        }

        // Flush last entry
        if (bodyBuilder != null && currentDirection != null) {
            entries.add(new TraceEntry(currentDirection, currentType, currentMethod,
                    currentId, bodyBuilder.toString().trim()));
        }

        return entries;
    }

    // ---- Data records ----

    /**
     * A single parsed trace entry (request, response, or notification).
     *
     * @param direction "Sending" or "Received"
     * @param type      "request", "response", or "notification"
     * @param method    the LSP/MCP method (e.g., "textDocument/references", "tools/call")
     * @param id        the request/response ID (null for notifications)
     * @param body      the JSON body (params for requests, result for responses)
     */
    public record TraceEntry(
            String direction,
            String type,
            String method,
            String id,
            String body
    ) {
    }
}
