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
package com.ibm.mcp.languagetools.mcp.trace;

import com.google.gson.JsonParser;
import com.ibm.mcp.languagetools.trace.AbstractTraceCollector;
import com.ibm.mcp.languagetools.trace.TraceKind;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.RawMessage;
import io.quarkus.logging.Log;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.ibm.mcp.languagetools.utils.JsonUtils.getPrettyPrintGson;

/**
 * Collects MCP traffic traces from the Quarkus MCP server traffic logger.
 * <p>
 * Uses connectionId as the contextId for trace messages.
 */
public class McpTraceCollector extends AbstractTraceCollector {

    /**
     * Timeout in milliseconds for orphaned pending requests (5 minutes).
     */
    private static final long PENDING_REQUEST_TIMEOUT_MS = 300_000;

    private final Map<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    private record PendingRequest(String method, Instant timestamp, String connectionId) {
    }

    @Override
    protected TraceKind getTraceKind() {
        return TraceKind.MCP;
    }

    /**
     * Add a new MCP trace with LSP-style formatting.
     *
     * @param direction whether the message was sent or received
     * @param message   the raw MCP message
     * @param connection the MCP connection (its ID is used as contextId)
     */
    public void addTrace(McpTraceDirection direction, RawMessage message, McpConnection connection) {
        if (!isEnabled()) {
            return;
        }
        String connectionId = connection.id();
        String formattedMessage = formatMessage(direction, message, connectionId, Instant.now());
        addTrace(null, connectionId, formattedMessage);
    }

    private String formatMessage(McpTraceDirection direction, RawMessage message, String connectionId, Instant now) {
        try {
            String method = message.method();
            String id = message.id() != null ? message.id().asString() : null;

            boolean isNotification = id == null && method != null;
            boolean isRequest = id != null && method != null;
            boolean isResponse = id != null && method == null;

            String header;
            String key = connectionId + ":" + (id != null ? id : "");

            if (isRequest) {
                header = String.format("[Trace - %s] Sending request '%s - (%s)'",
                        formatTime(now), method, id);
                cleanupOrphanedRequests(now);
                pendingRequests.put(key, new PendingRequest(method, now, connectionId));
            } else if (isResponse) {
                PendingRequest pending = pendingRequests.remove(key);
                if (pending != null) {
                    long durationMs = Duration.between(pending.timestamp, now).toMillis();
                    header = String.format("[Trace - %s] Received response '%s - (%s)' in %dms",
                            formatTime(now), pending.method, id, durationMs);
                } else {
                    header = String.format("[Trace - %s] Received response '(%s)'",
                            formatTime(now), id);
                }
            } else if (isNotification && direction == McpTraceDirection.SENT) {
                header = String.format("[Trace - %s] Sending notification '%s'",
                        formatTime(now), method);
            } else if (isNotification && direction == McpTraceDirection.RECEIVED) {
                header = String.format("[Trace - %s] Received notification '%s'",
                        formatTime(now), method);
            } else {
                header = String.format("[Trace - %s] MCP message %s [%s]",
                        formatTime(now), direction, connectionId);
            }

            String body = getBody(message);
            return header + "\n" + body;

        } catch (Exception e) {
            String body = message.asString();
            Log.errorf(e, "Failed to parse MCP message for formatting, direction=%s, connectionId=%s, messageStart=%s",
                    direction, connectionId, body.substring(0, Math.min(50, body.length())));
            return String.format("[Trace - %s] MCP %s (PARSE ERROR) [%s]\n%s",
                    formatTime(now), direction, connectionId, body);
        }
    }

    private static String getBody(RawMessage message) {
        try {
            return message.asJsonObject()
                    .encodePrettily();
        } catch (Exception e) {
            String jsonText = message.asString();
            try {
                var json = JsonParser.parseString(jsonText);
                return getPrettyPrintGson().toJson(json);
            } catch (Exception ex) {
                return jsonText;
            }
        }
    }

    /**
     * Remove pending requests older than {@link #PENDING_REQUEST_TIMEOUT_MS}.
     */
    private void cleanupOrphanedRequests(Instant now) {
        long nowMs = now.toEpochMilli();
        pendingRequests.entrySet().removeIf(entry ->
                nowMs - entry.getValue().timestamp.toEpochMilli() > PENDING_REQUEST_TIMEOUT_MS);
    }

    private String formatTime(Instant instant) {
        java.time.ZonedDateTime zdt = instant.atZone(java.time.ZoneId.systemDefault());
        return String.format("%02d:%02d:%02d",
                zdt.getHour(), zdt.getMinute(), zdt.getSecond());
    }
}
