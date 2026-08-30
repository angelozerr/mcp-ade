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
package com.ibm.mcp.languagetools.admin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.utils.UriUtils;
import com.ibm.mcp.languagetools.configuration.ApplicationConfiguration;
import com.ibm.mcp.languagetools.language.LanguageRegistry;
import com.ibm.mcp.languagetools.operation.OperationContext;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.operation.OperationStatus;
import com.ibm.mcp.languagetools.operation.OperationTracker;
import com.ibm.mcp.languagetools.trace.TraceMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.runtime.LaunchMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.ibm.mcp.languagetools.utils.JsonUtils.getPrettyPrintGson;

/**
 * REST endpoint for exporting MCP + LSP traces from a completed operation
 * as integration test trace files.
 * <p>
 * When the setting {@code trace.export.targetDir} is configured, files are
 * written directly to the test resources directory. Otherwise, a ZIP archive
 * is returned for download.
 */
@ApplicationScoped
@Path("/api/admin/traces/export")
@Produces(MediaType.APPLICATION_JSON)
public class TraceExportResource {

    private static final String SETTING_TARGET_DIR = "trace.export.targetDir";

    @Inject
    Application application;

    @Inject
    OperationTracker operationTracker;

    @Inject
    ApplicationConfiguration configuration;

    @Inject
    LanguageRegistry languageRegistry;

    @POST
    @Path("/{operationId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response exportOperation(
            @PathParam("operationId") String operationId,
            ExportRequest request) {

        OperationContext operation = operationTracker.findOperationById(operationId);
        if (operation == null) {
            return Response.status(404)
                    .entity(Map.of("error", "Operation not found"))
                    .build();
        }
        if (operation.getStatus() == OperationStatus.RUNNING) {
            return Response.status(400)
                    .entity(Map.of("error", "Operation is still running"))
                    .build();
        }

        String toolName = operation.getName();
        String testName = request != null && request.testName != null ? request.testName : "basic";
        String category = detectCategory(toolName);
        String langId = request != null && request.languageId != null
                ? request.languageId
                : detectLanguageId(operation);

        String workspaceUri = operation.getWorkspaceUri();
        String workspacePath = workspaceUri != null ? uriToPath(workspaceUri) : null;

        String mcpTrace = buildMcpTrace(operation, workspacePath);

        Map<String, String> lspTraces = buildLspTraces(operation, workspacePath);

        List<String> warnings = new ArrayList<>();
        if (lspTraces.isEmpty() && !operation.getEntries().isEmpty()) {
            warnings.add("No LSP traces found. Ensure LSP servers are set to 'verbose' trace level before calling the tool.");
        }

        String targetDir = resolveTargetDir();

        if (targetDir != null) {
            return writeToDirectory(targetDir, category, toolName, langId, testName,
                    mcpTrace, lspTraces, warnings);
        } else {
            return buildZipResponse(category, toolName, langId, testName,
                    mcpTrace, lspTraces, warnings);
        }
    }

    @GET
    @Path("/settings")
    public Response getExportSettings() {
        String targetDir = resolveTargetDir();
        boolean autoDetected = configuration.getString(SETTING_TARGET_DIR) == null && targetDir != null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetDir", targetDir != null ? targetDir : "");
        result.put("autoDetected", autoDetected);
        return Response.ok(result).build();
    }

    @PUT
    @Path("/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setExportSettings(Map<String, String> settings) {
        String targetDir = settings.get("targetDir");
        if (targetDir != null && !targetDir.isBlank()) {
            configuration.set(SETTING_TARGET_DIR, targetDir);
        } else {
            configuration.set(SETTING_TARGET_DIR, null);
        }
        return Response.noContent().build();
    }

    // ========== MCP Trace ==========

    private String buildMcpTrace(OperationContext operation, String workspacePath) {
        Gson gson = getPrettyPrintGson();
        StringBuilder sb = new StringBuilder();

        String startTime = formatTime(operation.getStartTime());
        String endTime = formatTime(operation.getEndTime());

        // Request
        JsonObject request = new JsonObject();
        request.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", operation.getName());
        if (operation.getArguments() != null) {
            params.add("arguments", gson.toJsonTree(operation.getArguments()));
        }
        request.add("params", params);
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", 1);

        sb.append("[Trace - ").append(startTime).append("] Sending request 'tools/call - (1)'\n");
        sb.append(gson.toJson(request));

        // Response
        sb.append("\n\n");
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.addProperty("id", 1);
        JsonObject result = new JsonObject();
        result.addProperty("isError", operation.getStatus() == OperationStatus.FAILED);
        JsonArray content = new JsonArray();
        JsonObject textContent = new JsonObject();
        String resultText = operation.getResult();
        if (resultText == null && operation.getError() != null) {
            resultText = operation.getError();
        }
        textContent.addProperty("text", resultText != null ? resultText : "");
        textContent.addProperty("type", "text");
        content.add(textContent);
        result.add("content", content);
        response.add("result", result);

        sb.append("[Trace - ").append(endTime).append("] Received response 'tools/call - (1)' in ")
                .append(operation.getDurationMs()).append("ms\n");
        sb.append(gson.toJson(response));

        return anonymizePaths(sb.toString(), workspacePath);
    }

    // ========== LSP Traces ==========

    private Map<String, String> buildLspTraces(OperationContext operation, String workspacePath) {
        Map<String, String> traces = new LinkedHashMap<>();

        Set<String> serverIds = new LinkedHashSet<>();
        for (OperationEntry entry : operation.getEntries()) {
            if (entry.getServerId() != null) {
                serverIds.add(entry.getServerId());
            }
        }

        for (String serverId : serverIds) {
            String trace = buildLspTraceForServer(serverId, operation, workspacePath);
            if (trace != null && !trace.isBlank()) {
                traces.put(serverId, trace);
            }
        }

        return traces;
    }

    private String buildLspTraceForServer(String serverId, OperationContext operation,
                                          String workspacePath) {
        List<TraceMessage> allTraces = application.getLspTraceCollector()
                .getTraces(operation.getWorkspaceUri(), serverId, 1000);

        if (allTraces.isEmpty()) {
            return null;
        }

        Instant opStart = operation.getStartTime();
        Instant opEnd = operation.getEndTime();

        // Collect initialize-related traces (before operation)
        List<TraceMessage> initTraces = new ArrayList<>();
        for (TraceMessage t : allTraces) {
            if (t.timestamp().isBefore(opStart) && isInitializeTrace(t.content())) {
                initTraces.add(t);
            }
        }
        // Keep only the most recent initialize sequence
        initTraces = keepLastInitializeSequence(initTraces);

        // Collect operation-time traces
        List<TraceMessage> opTraces = new ArrayList<>();
        for (TraceMessage t : allTraces) {
            if (!t.timestamp().isBefore(opStart) && !t.timestamp().isAfter(opEnd)) {
                opTraces.add(t);
            }
        }

        // Combine
        Set<TraceMessage> combined = new LinkedHashSet<>(initTraces);
        combined.addAll(opTraces);

        if (combined.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (TraceMessage t : combined) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(t.content());
        }

        return anonymizePaths(sb.toString(), workspacePath);
    }

    private boolean isInitializeTrace(String content) {
        return content.contains("'initialize -") || content.contains("'initialized'");
    }

    private List<TraceMessage> keepLastInitializeSequence(List<TraceMessage> traces) {
        if (traces.isEmpty()) return traces;

        // Find the last "Sending request 'initialize -" trace
        int lastInitRequest = -1;
        for (int i = traces.size() - 1; i >= 0; i--) {
            if (traces.get(i).content().contains("Sending request 'initialize -")) {
                lastInitRequest = i;
                break;
            }
        }

        if (lastInitRequest < 0) return traces;

        // Return from lastInitRequest to end (includes response + initialized)
        return traces.subList(lastInitRequest, traces.size());
    }

    // ========== Path Anonymization ==========

    private String anonymizePaths(String content, String workspacePath) {
        if (workspacePath == null || content == null) return content;

        // URI-encoded form: file:///C:/Users/...
        String uriPrefix = pathToUriPrefix(workspacePath);
        if (uriPrefix != null) {
            // Strip trailing slash so that file:///path/service.ts becomes
            // file:///${workspaceRoot}/service.ts (preserving the separator)
            if (uriPrefix.endsWith("/")) {
                uriPrefix = uriPrefix.substring(0, uriPrefix.length() - 1);
            }
            content = content.replace(uriPrefix, "file:///${workspaceRoot}");

            // file:/ variant (without authority) — normalize to file:///${workspaceRoot}
            String singleSlashPrefix = "file:/" + uriPrefix.substring("file:///".length());
            content = content.replace(singleSlashPrefix, "file:///${workspaceRoot}");
        }

        // Percent-encoded colon: file:///c%3A/Users/... (both upper and lower case drive letter)
        if (uriPrefix != null) {
            for (String base : new String[]{uriPrefix, uriPrefix.replaceFirst("(file:///)[A-Z]", "$1" + Character.toLowerCase(uriPrefix.charAt(8)))}) {
                String encoded = base.replaceFirst("(file:///[a-zA-Z]):", "$1%3A");
                if (!encoded.equals(base)) {
                    content = content.replace(encoded, "file:///${workspaceRoot}");
                    // Also file:/ variant of encoded form
                    String encodedSingleSlash = "file:/" + encoded.substring("file:///".length());
                    content = content.replace(encodedSingleSlash, "file:///${workspaceRoot}");
                }
            }
        }

        // JSON-escaped Windows path with doubled backslashes: C:\\Users\\...
        String escapedPath = workspacePath.replace("\\", "\\\\");
        content = content.replace(escapedPath, "${workspaceRoot}");

        // Windows path with backslashes: C:\Users\...
        content = content.replace(workspacePath, "${workspaceRoot}");

        // Path with forward slashes: C:/Users/...
        String forwardSlashPath = workspacePath.replace('\\', '/');
        content = content.replace(forwardSlashPath, "${workspaceRoot}");

        // Forward slashes with lowercase drive letter: c:/Users/...
        if (forwardSlashPath.length() > 1 && Character.isUpperCase(forwardSlashPath.charAt(0))) {
            String lowerDrive = Character.toLowerCase(forwardSlashPath.charAt(0)) + forwardSlashPath.substring(1);
            content = content.replace(lowerDrive, "${workspaceRoot}");
        }

        return content;
    }

    private String pathToUriPrefix(String workspacePath) {
        try {
            return UriUtils.toFileUriString(java.nio.file.Path.of(workspacePath).toUri());
        } catch (Exception e) {
            return null;
        }
    }

    // ========== Category / Language Detection ==========

    private String detectCategory(String toolName) {
        if (toolName != null && toolName.startsWith("java_")) {
            return "java-tools";
        }
        // TODO: detect DAP tools
        return "lsp-tools";
    }

    private String detectLanguageId(OperationContext operation) {
        Map<String, Object> args = operation.getArguments();
        if (args != null && args.containsKey("uri")) {
            Object uriObj = args.get("uri");
            if (uriObj instanceof String uriStr) {
                try {
                    return languageRegistry.detectLanguage(URI.create(uriStr))
                            .orElse("unknown");
                } catch (Exception ignored) {
                }
            }
        }
        return "unknown";
    }

    // ========== Write to Directory ==========

    private Response writeToDirectory(String targetDir, String category, String toolName,
                                      String langId, String testName, String mcpTrace,
                                      Map<String, String> lspTraces, List<String> warnings) {
        java.nio.file.Path dir = java.nio.file.Path.of(targetDir, category, toolName, langId, testName);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("mcp.trace"), mcpTrace);
            for (var entry : lspTraces.entrySet()) {
                Files.writeString(dir.resolve(entry.getKey() + ".trace"), entry.getValue());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "exported");
            result.put("path", dir.toString());
            result.put("files", lspTraces.size() + 1);
            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }
            return Response.ok(result).build();
        } catch (IOException e) {
            return Response.status(500)
                    .entity(Map.of("error", "Failed to write: " + e.getMessage()))
                    .build();
        }
    }

    // ========== ZIP Response ==========

    private Response buildZipResponse(String category, String toolName, String langId,
                                      String testName, String mcpTrace,
                                      Map<String, String> lspTraces, List<String> warnings) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                String prefix = category + "/" + toolName + "/" + langId + "/" + testName + "/";

                zos.putNextEntry(new ZipEntry(prefix + "mcp.trace"));
                zos.write(mcpTrace.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();

                for (var entry : lspTraces.entrySet()) {
                    zos.putNextEntry(new ZipEntry(prefix + entry.getKey() + ".trace"));
                    zos.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }

            String filename = "traces-" + toolName + "-" + testName + ".zip";
            return Response.ok(baos.toByteArray(), "application/zip")
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .build();
        } catch (IOException e) {
            return Response.status(500)
                    .entity(Map.of("error", "Failed to create ZIP: " + e.getMessage()))
                    .build();
        }
    }

    // ========== Target Directory Resolution ==========

    private static final String TRACES_RELATIVE_PATH = "integration-tests/src/test/resources/traces";

    private String resolveTargetDir() {
        String configured = configuration.getString(SETTING_TARGET_DIR);
        if (configured != null) {
            return configured;
        }
        return autoDetectTargetDir();
    }

    private String autoDetectTargetDir() {
        if (LaunchMode.current() != LaunchMode.DEVELOPMENT) {
            return null;
        }
        // Walk up from user.dir to find the multi-module project root
        // (user.dir may be a sub-module like dev/)
        java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.dir"));
        while (dir != null) {
            java.nio.file.Path candidate = dir.resolve(TRACES_RELATIVE_PATH);
            if (Files.isDirectory(candidate.getParent())) {
                try {
                    Files.createDirectories(candidate);
                } catch (IOException ignored) {
                }
                return candidate.toString();
            }
            dir = dir.getParent();
        }
        return null;
    }

    // ========== Helpers ==========

    private String uriToPath(String uri) {
        if (uri == null) return null;
        try {
            return java.nio.file.Path.of(URI.create(uri)).toString();
        } catch (Exception e) {
            // Try stripping file:/// prefix directly
            if (uri.startsWith("file:///")) {
                return uri.substring("file:///".length()).replace('/', java.io.File.separatorChar);
            }
            return null;
        }
    }

    private String formatTime(Instant instant) {
        if (instant == null) return "00:00:00";
        ZonedDateTime zdt = instant.atZone(ZoneId.systemDefault());
        return String.format("%02d:%02d:%02d", zdt.getHour(), zdt.getMinute(), zdt.getSecond());
    }

    // ========== Request DTO ==========

    public static class ExportRequest {
        public String testName;
        public String languageId;
    }
}
