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

import java.util.List;
import java.util.function.Consumer;

/**
 * Collects trace messages from LSP/DAP servers.
 * <p>
 * Traces are identified by a workspace URI and a context ID:
 * <ul>
 *   <li>For LSP: contextId is the server ID (e.g. "jdtls")</li>
 *   <li>For DAP: contextId is "serverId#sessionId" for protocol traces, or just "serverId" for installation traces</li>
 * </ul>
 * <p>
 * Overloads without workspaceUri pass null, for traces not tied
 * to a specific workspace (e.g. installation traces).
 */
public interface TraceCollector {

    /**
     * Type of trace message, used to control display in the admin UI.
     */
    enum MessageType {
        /** Standard protocol trace (request/response/notification). */
        TRACE,
        /** Progress update during installation. */
        UPDATE,
        /** Error message. */
        ERROR,
        /** Informational message (e.g. installation step). */
        INFO
    }

    /**
     * Returns whether this trace collector is enabled.
     * When false, callers should skip trace message creation entirely
     * to avoid unnecessary allocations.
     */
    boolean isEnabled();

    /**
     * Add a trace message with full context.
     *
     * @param workspaceUri the workspace URI, or null for global traces
     * @param contextId    the server ID (LSP), "serverId#sessionId" (DAP protocol), or serverId (DAP installation)
     * @param content      the trace content
     * @param type         the message type
     */
    void addTrace(String workspaceUri, String contextId, String content, MessageType type);

    /**
     * Add a trace message with default type {@link MessageType#TRACE}.
     */
    default void addTrace(String workspaceUri, String contextId, String content) {
        addTrace(workspaceUri, contextId, content, MessageType.TRACE);
    }

    /**
     * Add a trace message without workspace context.
     */
    default void addTrace(String contextId, String content, MessageType type) {
        addTrace(null, contextId, content, type);
    }

    /**
     * Add a trace message without workspace context, with default type {@link MessageType#TRACE}.
     */
    default void addTrace(String contextId, String content) {
        addTrace(null, contextId, content, MessageType.TRACE);
    }

    /**
     * Register a listener notified on each new trace message.
     */
    default void addTraceListener(Consumer<TraceMessage> listener) {
    }

    /**
     * Remove a previously registered trace listener.
     */
    default void removeTraceListener(Consumer<TraceMessage> listener) {
    }

    /**
     * Get the most recent traces, limited to the given count.
     */
    default List<TraceMessage> getTraces(int limit) {
        return List.of();
    }

    /**
     * Get traces filtered by workspace and context.
     */
    default List<TraceMessage> getTraces(String workspaceUri, String contextId, int limit) {
        return List.of();
    }

    /**
     * Get traces for a DAP session (installation + protocol traces).
     */
    default List<TraceMessage> getTracesForSession(String serverId, String sessionId, int limit) {
        return List.of();
    }

    /**
     * Clear all stored traces.
     */
    default void clear() {
    }
}
