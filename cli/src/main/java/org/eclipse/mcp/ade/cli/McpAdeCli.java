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
package org.eclipse.mcp.ade.cli;

import picocli.CommandLine;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.StringReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * MCP ADE CLI — command-line tool for admin operations.
 * <p>
 * Connects to a running MCP ADE server and executes commands.
 * <p>
 * Usage: {@code mcpade <domain> <action> [--options]}
 */
@CommandLine.Command(
        name = "mcpade",
        mixinStandardHelpOptions = true,
        version = "mcpade 0.1.0",
        description = "MCP ADE (Agent Development Environment) CLI")
public class McpAdeCli implements Callable<Integer> {

    @Option(names = {"--port"}, description = "Server port (default: 7654)",
            defaultValue = "7654")
    private int port;

    @Option(names = {"--url"}, description = "Server URL (overrides --port, for remote servers)")
    private String url;

    @Option(names = {"--json"}, description = "Output in JSON format")
    private boolean jsonOutput;

    @Parameters(index = "0", arity = "0..1", description = "Command domain (lsp, dap, bsp, extension)")
    private String domain;

    @Parameters(index = "1", arity = "0..1", description = "Command action (list, add, remove, enable, disable)")
    private String action;

    @CommandLine.Unmatched
    private String[] extraArgs;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private String getServerUrl() {
        if (url != null) {
            return url;
        }
        return "http://localhost:" + port;
    }

    @Override
    public Integer call() {
        try {
            if (domain == null) {
                return showHelp();
            }
            if (action == null) {
                return showDomainHelp(domain);
            }
            return executeCommand(domain, action);
        } catch (ConnectException e) {
            System.err.println("Error: Cannot connect to MCP ADE server at " + getServerUrl());
            System.err.println("Make sure the server is running, or specify --url <server-url>");
            return 1;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int showHelp() {
        System.out.println("mcpade — MCP ADE (Agent Development Environment) CLI");
        System.out.println();
        System.out.println("Usage: mcpade <domain> <action> [--options]");
        System.out.println();
        System.out.println("Domains:");
        System.out.println("  extension      Manage extensions (list, add, remove, enable, disable, schemas)");
        System.out.println("  lsp            Manage LSP servers (list, add, remove, enable, disable)");
        System.out.println("  dap            Manage DAP servers (list, add, remove, enable, disable)");
        System.out.println("  bsp            Manage BSP servers (list, add, remove, enable, disable)");
        System.out.println();

        // Try to enrich with live commands from server
        try {
            String response = httpGet("/api/commands");
            if (jsonOutput) {
                System.out.println(response);
                return 0;
            }
            Map<String, List<String[]>> byDomain = parseCommandList(response);
            if (!byDomain.isEmpty()) {
                System.out.println("Commands (from server at " + getServerUrl() + "):");
                for (Map.Entry<String, List<String[]>> entry : byDomain.entrySet()) {
                    System.out.println();
                    System.out.println("  " + entry.getKey());
                    for (String[] cmd : entry.getValue()) {
                        System.out.printf("    %-12s %s%n", cmd[0], cmd[1]);
                    }
                }
                System.out.println();
            }
        } catch (Exception e) {
            System.out.println("  (server not running at " + getServerUrl() + " — start it for full command details)");
            System.out.println();
        }

        System.out.println("Options:");
        System.out.println("  --port <port>  Server port (default: 7654)");
        System.out.println("  --url <url>    Server URL (overrides --port, for remote servers)");
        System.out.println("  --json         Output in JSON format");
        System.out.println("  --help         Show this help");
        System.out.println("  --version      Show version");
        return 0;
    }

    private int showDomainHelp(String domain) throws Exception {
        String response = httpGet("/api/commands");
        if (jsonOutput) {
            System.out.println(response);
            return 0;
        }

        Map<String, List<String[]>> byDomain = parseCommandList(response);
        List<String[]> commands = byDomain.get(domain);
        if (commands == null || commands.isEmpty()) {
            System.err.println("Unknown domain: " + domain);
            System.err.println("Available domains: " + String.join(", ", byDomain.keySet()));
            return 1;
        }

        System.out.println("mcpade " + domain + " — available commands:");
        System.out.println();
        for (String[] cmd : commands) {
            System.out.printf("  mcpade %s %-12s %s%n", domain, cmd[0], cmd[1]);
            if (cmd[2] != null && !cmd[2].isEmpty()) {
                System.out.println("    " + cmd[2]);
            }
        }
        return 0;
    }

    private int executeCommand(String domain, String action) throws Exception {
        Map<String, String> args = parseExtraArgs();
        String body = toJson(args);
        String response = httpPost("/api/commands/" + domain + "/" + action, body);
        System.out.println(response);
        return 0;
    }

    /**
     * Parses the JSON array of commands into a map grouped by domain.
     * Each entry is [action, description, argsDescription].
     * Simple JSON parsing without external dependencies.
     */
    private Map<String, List<String[]>> parseCommandList(String json) {
        Map<String, List<String[]>> result = new LinkedHashMap<>();
        // Minimal JSON array parsing for command list
        int i = 0;
        while (i < json.length()) {
            int domainStart = json.indexOf("\"domain\"", i);
            if (domainStart < 0) break;

            String domain = extractJsonString(json, domainStart);
            String action = extractJsonString(json, json.indexOf("\"action\"", domainStart));
            String description = extractJsonString(json, json.indexOf("\"description\"", domainStart));

            // Parse args for this command
            StringBuilder argsDesc = new StringBuilder();
            int argsStart = json.indexOf("\"args\"", domainStart);
            if (argsStart > 0 && argsStart < json.indexOf("}", domainStart + 50) + 100) {
                int arrStart = json.indexOf("[", argsStart);
                int arrEnd = json.indexOf("]", arrStart);
                if (arrStart > 0 && arrEnd > 0) {
                    String argsSection = json.substring(arrStart, arrEnd + 1);
                    int nameIdx = 0;
                    while ((nameIdx = argsSection.indexOf("\"name\"", nameIdx)) >= 0) {
                        String argName = extractJsonString(argsSection, nameIdx);
                        boolean required = argsSection.indexOf("true", nameIdx) <
                                argsSection.indexOf("\"name\"", nameIdx + 10)
                                || argsSection.indexOf("\"name\"", nameIdx + 10) < 0;
                        if (!argsDesc.isEmpty()) argsDesc.append(" ");
                        if (required) {
                            argsDesc.append("--").append(argName).append(" <").append(argName).append(">");
                        } else {
                            argsDesc.append("[--").append(argName).append(" <").append(argName).append(">]");
                        }
                        nameIdx += 6;
                    }
                }
            }

            result.computeIfAbsent(domain, k -> new ArrayList<>())
                    .add(new String[]{action, description, argsDesc.toString()});

            i = domainStart + 10;
        }
        return result;
    }

    private static String extractJsonString(String json, int keyStart) {
        if (keyStart < 0) return "";
        int colonPos = json.indexOf(":", keyStart);
        if (colonPos < 0) return "";
        int quoteStart = json.indexOf("\"", colonPos + 1);
        if (quoteStart < 0) return "";
        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteEnd < 0) return "";
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private Map<String, String> parseExtraArgs() {
        Map<String, String> args = new LinkedHashMap<>();
        if (extraArgs == null) {
            return args;
        }
        for (int i = 0; i < extraArgs.length; i++) {
            String arg = extraArgs[i];
            if (arg.startsWith("--") && i + 1 < extraArgs.length) {
                args.put(arg.substring(2), extraArgs[++i]);
            }
        }
        return args;
    }

    private String httpGet(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getServerUrl() + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException(response.body());
        }
        return response.body();
    }

    private String httpPost(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getServerUrl() + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException(response.body());
        }
        return response.body();
    }

    private static String toJson(Map<String, String> map) {
        if (map.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey()))
              .append("\":\"").append(escapeJson(entry.getValue())).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new McpAdeCli()).execute(args);
        System.exit(exitCode);
    }
}
