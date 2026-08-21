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
package com.ibm.mcp.languagetools.trace;

import com.ibm.mcp.languagetools.utils.JsonUtils;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageConsumer;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * Traces LSP messages in a format similar to lsp4ij.
 * Adapted from lsp4ij TracingMessageConsumer.
 */
public class TracingMessageConsumer {

    private static volatile MessageJsonHandler toStringInstance;

    private final TraceCollector collector;
    private final String workspaceUri;

    /**
     * The context ID for traces:
     * <ul>
     *   <li>LSP: the server ID (e.g. "jdtls")</li>
     *   <li>DAP: "serverId#sessionId" (e.g. "js-debug#session-123")</li>
     * </ul>
     */
    private final String contextId;
    private final PendingRequestTracker<RequestMetadata> sentRequests;
    private final PendingRequestTracker<RequestMetadata> receivedRequests;
    private final Clock clock;
    private final DateTimeFormatter dateTimeFormatter;

    public TracingMessageConsumer(TraceCollector collector, String workspaceUri, String contextId) {
        this.collector = collector;
        this.workspaceUri = workspaceUri;
        this.contextId = contextId;
        this.sentRequests = new PendingRequestTracker<>(m -> m.start);
        this.receivedRequests = new PendingRequestTracker<>(m -> m.start);
        this.clock = Clock.systemDefaultZone();
        this.dateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(clock.getZone());
    }

    /**
     * Log a message and determine direction based on MessageConsumer type.
     */
    public void log(Message message, MessageConsumer messageConsumer) {
        if (!collector.isEnabled()) {
            return;
        }
        final Instant now = clock.instant();
        final String date = dateTimeFormatter.format(now);

        String logContent;

        if (messageConsumer instanceof StreamMessageConsumer) {
            logContent = consumeMessageSending(message, now, date);
        } else if (messageConsumer instanceof RemoteEndpoint) {
            logContent = consumeMessageReceiving(message, now, date);
        } else {
            logContent = String.format("Unknown MessageConsumer type: %s", messageConsumer);
        }

        collector.addTrace(workspaceUri, contextId, logContent);
    }

    private String consumeMessageSending(Message message, Instant now, String date) {
        if (message instanceof RequestMessage) {
            RequestMessage requestMessage = (RequestMessage) message;
            String id = requestMessage.getId();
            String method = requestMessage.getMethod();
            RequestMetadata requestMetadata = new RequestMetadata(method, now);
            sentRequests.track(id, requestMetadata, now);
            Object params = requestMessage.getParams();
            String paramsJson = toJsonString(params);
            return String.format("[Trace - %s] Sending request '%s - (%s)'.\nParams: %s\n\n", date, method, id, paramsJson);
        } else if (message instanceof ResponseMessage) {
            ResponseMessage responseMessage = (ResponseMessage) message;
            String id = responseMessage.getId();
            RequestMetadata requestMetadata = receivedRequests.resolve(id);
            String method = getMethod(requestMetadata);
            String latencyMillis = getLatencyMillis(requestMetadata, now);
            Object result = responseMessage.getResult();
            String resultJson = toJsonString(result);
            String resultTrace = getResultTrace(resultJson, null);
            return String.format("[Trace - %s] Sending response '%s - (%s)'. Processing request took %sms\n%s\n\n", date, method, id, latencyMillis, resultTrace);
        } else if (message instanceof NotificationMessage) {
            NotificationMessage notificationMessage = (NotificationMessage) message;
            String method = notificationMessage.getMethod();
            Object params = notificationMessage.getParams();
            String paramsJson = toJsonString(params);
            return String.format("[Trace - %s] Sending notification '%s'\nParams: %s\n\n", date, method, paramsJson);
        } else {
            return String.format("Unknown message type: %s", message);
        }
    }

    private String consumeMessageReceiving(Message message, Instant now, String date) {
        if (message instanceof RequestMessage) {
            RequestMessage requestMessage = (RequestMessage) message;
            String method = requestMessage.getMethod();
            String id = requestMessage.getId();
            RequestMetadata requestMetadata = new RequestMetadata(method, now);
            receivedRequests.track(id, requestMetadata, now);
            Object params = requestMessage.getParams();
            String paramsJson = toJsonString(params);
            return String.format("[Trace - %s] Received request '%s - (%s)'\nParams: %s\n\n", date, method, id, paramsJson);
        } else if (message instanceof ResponseMessage) {
            ResponseMessage responseMessage = (ResponseMessage) message;
            String id = responseMessage.getId();
            RequestMetadata requestMetadata = sentRequests.resolve(id);
            String method = getMethod(requestMetadata);
            String latencyMillis = getLatencyMillis(requestMetadata, now);
            Object result = responseMessage.getResult();
            String resultJson = toJsonString(result);
            Object error = responseMessage.getError();
            String errorJson = toJsonString(error);
            String resultTrace = getResultTrace(resultJson, errorJson);
            return String.format("[Trace - %s] Received response '%s - (%s)' in %sms.\n%s\n\n", date, method, id, latencyMillis, resultTrace);
        } else if (message instanceof NotificationMessage) {
            NotificationMessage notificationMessage = (NotificationMessage) message;
            String method = notificationMessage.getMethod();
            Object params = notificationMessage.getParams();
            String paramsJson = toJsonString(params);
            return String.format("[Trace - %s] Received notification '%s'\nParams: %s\n\n", date, method, paramsJson);
        } else {
            return String.format("Unknown message type: %s", message);
        }
    }

    /**
     * Trace a programmatic request (e.g., bind request to a delegate command handler).
     */
    public void traceRequest(String method, Object params, boolean verbose) {
        if (!collector.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        String date = dateTimeFormatter.format(now);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[Trace - %s] Sending request '%s'.", date, method));
        if (verbose) {
            sb.append("\nParams: ").append(toJsonString(params));
        }
        sb.append("\n\n");
        collector.addTrace(workspaceUri, contextId, sb.toString());
    }

    /**
     * Trace a programmatic response (e.g., bind response from a delegate command handler).
     */
    public void traceResponse(String method, Object result, Throwable error, long durationMs, boolean verbose) {
        if (!collector.isEnabled()) {
            return;
        }
        Instant now = clock.instant();
        String date = dateTimeFormatter.format(now);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[Trace - %s] Received response '%s' in %dms.", date, method, durationMs));
        if (error != null) {
            sb.append("\nError: ").append(error.getMessage());
        } else if (verbose) {
            String resultJson = toJsonString(result);
            sb.append("\n").append(getResultTrace(resultJson, null));
        }
        sb.append("\n\n");
        collector.addTrace(workspaceUri, contextId, sb.toString());
    }

    private static String toJsonString(Object object) {
        MessageJsonHandler instance = toStringInstance;
        if (instance == null) {
            synchronized (TracingMessageConsumer.class) {
                instance = toStringInstance;
                if (instance == null) {
                    instance = new MessageJsonHandler(Collections.emptyMap(), gsonBuilder -> {
                        JsonUtils.configureGson(gsonBuilder);
                        gsonBuilder.setPrettyPrinting();
                    });
                    toStringInstance = instance;
                }
            }
        }
        return instance.getGson().toJson(object);
    }

    private static String getResultTrace(String resultJson, String errorJson) {
        StringBuilder result = new StringBuilder();
        if (resultJson != null && !"null".equals(resultJson)) {
            result.append("Result: ");
            result.append(resultJson);
        } else {
            result.append("No response returned.");
        }
        if (errorJson != null && !"null".equals(errorJson)) {
            result.append("\nError: ");
            result.append(errorJson);
        }
        return result.toString();
    }

    private static String getMethod(RequestMetadata requestMetadata) {
        return requestMetadata != null ? requestMetadata.method : "<unknown>";
    }

    private static String getLatencyMillis(RequestMetadata requestMetadata, Instant now) {
        return requestMetadata != null ? String.valueOf(now.toEpochMilli() - requestMetadata.start.toEpochMilli()) : "?";
    }

    private static class RequestMetadata {
        final String method;
        final Instant start;

        public RequestMetadata(String method, Instant start) {
            this.method = method;
            this.start = start;
        }
    }
}
