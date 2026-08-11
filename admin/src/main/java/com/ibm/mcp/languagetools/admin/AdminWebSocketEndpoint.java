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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.admin.dto.McpClientDTO;
import com.ibm.mcp.languagetools.admin.dto.WorkspaceDTO;
import com.ibm.mcp.languagetools.admin.ws.*;
import com.ibm.mcp.languagetools.dap.session.DapSessionEvent;
import com.ibm.mcp.languagetools.dap.session.DapSessionManager;
import com.ibm.mcp.languagetools.event.ServerEnabledChangeEvent;
import com.ibm.mcp.languagetools.lsp.server.LspServerStatusChangeEvent;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.configuration.ApplicationConfiguration;
import com.ibm.mcp.languagetools.operation.OperationTracker;
import com.ibm.mcp.languagetools.trace.TraceMessage;
import com.ibm.mcp.languagetools.workspace.WorkspaceChangeEvent;
import io.quarkiverse.mcp.server.runtime.ConnectionManager;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint for real-time admin UI updates.
 * Replaces SSE streams and polling with a single bidirectional connection.
 */
@ServerEndpoint("/api/admin/ws")
@ApplicationScoped
public class AdminWebSocketEndpoint {

    private static final Logger LOG = Logger.getLogger(AdminWebSocketEndpoint.class);

    @Inject
    Application application;

    @Inject
    ConnectionManager connectionManager;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DapSessionManager dapSessionManager;

    @Inject
    AdminProgressBroadcaster progressBroadcaster;

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @Inject
    OperationTracker operationTracker;

    // Thread-safe set of active WebSocket sessions
    private final Set<Session> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @PostConstruct
    void init() {
        application.getLspTraceCollector().addTraceListener(this::onTrace);
        application.getDapTraceCollector().addTraceListener(this::onTrace);
        application.getMcpTraceCollector().addTraceListener(this::onTrace);
        operationTracker.addListener(event -> {
            OperationUpdateWsMessage msg = new OperationUpdateWsMessage(
                    event.type().name(), event.operation());
            broadcast(msg);
        });
    }

    @OnOpen
    public void onOpen(Session session) {
        sessions.add(session);
        LOG.infof("WebSocket client connected: %s (total: %d)", session.getId(), sessions.size());

        // Send initial state snapshot
        sendInitialState(session);
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        sessions.remove(session);
        LOG.infof("WebSocket client disconnected: %s, reason: %s (remaining: %d)",
                session.getId(), closeReason, sessions.size());
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        LOG.errorf(throwable, "WebSocket error for session: %s", session.getId());
        sessions.remove(session);
    }

    /**
     * Send initial state when client connects.
     */
    private void sendInitialState(Session session) {
        try {
            // Send current workspaces
            WorkspacesUpdateWsMessage workspacesMsg = new WorkspacesUpdateWsMessage(
                    getCurrentWorkspaces()
            );
            sendToSession(session, workspacesMsg);

            // Send current MCP clients
            McpClientsUpdateWsMessage clientsMsg = new McpClientsUpdateWsMessage(
                    getCurrentMcpClients()
            );
            sendToSession(session, clientsMsg);

            // Send trace levels early so the UI has them before trace history
            sendTraceLevels(session);

            // Send LSP trace history for all servers
            sendLspTraceHistory(session);

            // Send MCP trace history
            sendMcpTraceHistory(session);

            // Send DAP trace history
            sendDapTraceHistory(session);

            // Send active progress state
            sendProgressState(session);

            // Send activity state
            sendToSession(session, new com.ibm.mcp.languagetools.admin.ws.ActivityStateWsMessage(
                    operationTracker.isEnabled()));

            // Send recent operations
            sendOperationHistory(session);

            LOG.debugf("Initial state sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send initial state to session: %s", session.getId());
        }
    }

    /**
     * Send LSP trace history for all servers.
     */
    private void sendLspTraceHistory(Session session) {
        try {
            // Get all workspaces and their servers
            for (var workspace : application.getWorkspaces()) {
                for (var server : workspace.getLspServers()) {
                    // Get last 200 traces for this server
                    var traces = application.getLspTraceCollector().getTraces(workspace.getNormalizedUri(), server.getId(), 200);

                    for (var trace : traces) {
                        LspTraceWsMessage msg = new LspTraceWsMessage(
                                trace.workspaceUri(),
                                trace.contextId(),
                                trace.content(),
                                trace.messageType()
                        );
                        sendToSession(session, msg);
                    }
                }
            }
            LOG.debugf("LSP trace history sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send LSP trace history to session: %s", session.getId());
        }
    }

    /**
     * Send MCP trace history.
     */
    private void sendMcpTraceHistory(Session session) {
        try {
            var traces = application.getMcpTraceCollector().getTraces(500);

            for (var trace : traces) {
                McpTraceWsMessage msg = new McpTraceWsMessage(
                        trace.contextId(),
                        trace.content()
                );
                sendToSession(session, msg);
            }
            LOG.debugf("MCP trace history sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send MCP trace history to session: %s", session.getId());
        }
    }

    /**
     * Send DAP trace history for all sessions.
     */
    private void sendDapTraceHistory(Session session) {
        try {
            var dapSessions = dapSessionManager.getAllSessions();
            LOG.debugf("Sending DAP trace history: %d sessions", dapSessions.size());

            for (var dapSession : dapSessions) {
                String serverId = dapSession.getDapServer() != null
                        ? dapSession.getDapServer().getConfig().getServerId() : null;
                if (serverId == null) {
                    continue;
                }

                // Get both installation traces (contextId=serverId) and protocol traces (contextId=serverId#sessionId)
                var traces = application.getDapTraceCollector().getTracesForSession(serverId, dapSession.getSessionId(), 200);

                LOG.debugf("Session %s: sending %d traces", dapSession.getSessionId(), traces.size());

                for (var trace : traces) {
                    sendToSession(session, toDapTraceWsMessage(trace));
                }
            }
            LOG.debugf("DAP trace history sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send DAP trace history to session: %s", session.getId());
        }
    }

    /**
     * Send active progress state (init + last update) for tasks currently in progress.
     */
    private void sendProgressState(Session session) {
        try {
            for (AdminProgressBroadcaster.ActiveTask task : progressBroadcaster.getActiveTasks()) {
                if (task.getInitMessage() != null) {
                    sendToSession(session, task.getInitMessage());
                }
                if (task.getLastUpdate() != null) {
                    sendToSession(session, task.getLastUpdate());
                }
            }
            LOG.debugf("Progress state sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send progress state to session: %s", session.getId());
        }
    }

    /**
     * Send saved trace levels to a newly connected session.
     * Parses settings keys like "lsp.serverId.trace", "dap.serverId.trace", "mcp.trace".
     */
    private void sendTraceLevels(Session session) {
        try {
            for (var entry : applicationConfiguration.getTraceLevelEntries().entrySet()) {
                String key = entry.getKey();
                String traceLevel = entry.getValue();
                TraceLevelWsMessage msg = parseTraceLevelKey(key, traceLevel);
                if (msg != null) {
                    sendToSession(session, msg);
                }
            }
            LOG.debugf("Trace levels sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send trace levels to session: %s", session.getId());
        }
    }

    private TraceLevelWsMessage parseTraceLevelKey(String key, String traceLevel) {
        // key format: "lsp.serverId.trace", "dap.serverId.trace", "mcp.trace"
        if (key.equals("mcp.trace")) {
            return new TraceLevelWsMessage("mcp", null, traceLevel);
        }
        if (key.startsWith("lsp.") && key.endsWith(".trace")) {
            String serverId = key.substring(4, key.length() - 6);
            return new TraceLevelWsMessage("lsp", serverId, traceLevel);
        }
        if (key.startsWith("dap.") && key.endsWith(".trace")) {
            String serverId = key.substring(4, key.length() - 6);
            return new TraceLevelWsMessage("dap", serverId, traceLevel);
        }
        return null;
    }

    /**
     * CDI observer for trace level changes — broadcasts to all clients.
     */
    void onTraceLevelUpdate(@Observes TraceLevelWsMessage msg) {
        broadcast(msg);
    }

    /**
     * Listener callback for LSP/DAP/MCP trace events (registered via addTraceListener).
     */
    private void onTrace(TraceMessage trace) {
        switch (trace.kind()) {
            case LSP -> broadcast(new LspTraceWsMessage(
                    trace.workspaceUri(), trace.contextId(),
                    trace.content(), trace.messageType()));
            case DAP -> broadcast(toDapTraceWsMessage(trace));
            case MCP -> broadcast(new McpTraceWsMessage(
                    trace.contextId(), trace.content()));
        }
    }

    /**
     * CDI observer for DAP session events (created/changed/deleted).
     */
    void onDapSessionEvent(@Observes DapSessionEvent event) {
        LOG.infof("DAP session event: %s - session=%s, workspace=%s, status=%s->%s",
                event.getType(), event.getSessionId(), event.getWorkspaceUri(),
                event.getOldStatus(), event.getNewStatus());

        var msg = new DapSessionUpdateWsMessage(
                event.getType().name(),
                event.getSessionId(),
                event.getWorkspaceUri(),
                event.getOldStatus(),
                event.getNewStatus(),
                event.getDebugMode(),
                event.getCreatedBy(),
                event.getCreatedAt(),
                event.getLaunchedBy(),
                event.getLaunchedAt()
        );
        broadcast(msg);
    }

    /**
     * CDI observer for progress updates.
     */
    void onProgressUpdate(@Observes ProgressUpdateWsMessage msg) {
        broadcast(msg);
    }

    /**
     * CDI observer for progress initialization (steps definition).
     */
    void onProgressInit(@Observes ProgressInitWsMessage msg) {
        broadcast(msg);
    }

    /**
     * CDI observer for workspace changes (created/closed).
     */
    void onWorkspaceChange(@Observes WorkspaceChangeEvent event) {
        LOG.infof("Workspace changed: %s - %s", event.type(), event.workspaceUri());

        // Send full workspace list (simpler than delta updates)
        WorkspacesUpdateWsMessage msg = new WorkspacesUpdateWsMessage(
                getCurrentWorkspaces()
        );
        broadcast(msg);

        // Also send updated MCP clients list (tied to workspaces)
        McpClientsUpdateWsMessage clientsMsg = new McpClientsUpdateWsMessage(
                getCurrentMcpClients()
        );
        broadcast(clientsMsg);
    }

    /**
     * CDI observer for server status changes.
     */
    void onServerStatusChange(@Observes LspServerStatusChangeEvent event) {
        LOG.infof("WebSocket: Server status changed: %s/%s - %s -> %s (broadcasting to %d clients)",
                event.workspaceUri(), event.serverId(), event.oldStatus(), event.newStatus(), sessions.size());

        // Get server details for progress info
        var workspace = application.getWorkspace(event.workspaceUri());
        String statusMessage = null;
        Double installProgress = null;
        Boolean isReady = false;

        if (workspace != null) {
            var server = workspace.getLspServer(event.serverId());
            if (server != null) {
                statusMessage = server.getStatusMessage();
                isReady = server.isReady();
                LOG.infof("WebSocket: statusMessage='%s', isReady=%s for %s", statusMessage, isReady, event.serverId());

                // Get install progress if installing
                if (event.newStatus() == ServerStatus.INSTALLING) {
                    var config = server.getConfig();
                    var progressIndicator = config.getInstallProgress();
                    if (progressIndicator != null) {
                        installProgress = progressIndicator.getFraction();
                    }
                }
            } else {
                LOG.warnf("WebSocket: server '%s' not found in workspace", event.serverId());
            }
        } else {
            LOG.warnf("WebSocket: workspace not found for URI: %s", event.workspaceUri());
        }

        // Send status change event with progress info
        ServerStatusChangedWsMessage msg = new ServerStatusChangedWsMessage(
                event.workspaceUri().toString(),
                event.serverId(),
                event.oldStatus().name(),
                event.newStatus().name(),
                statusMessage,
                installProgress,
                isReady
        );
        try {
            LOG.infof("WebSocket: broadcasting status JSON: %s", objectMapper.writeValueAsString(msg));
        } catch (Exception e) {
            LOG.warnf("WebSocket: failed to log status JSON");
        }
        broadcast(msg);
    }

    void onActivityStateChange(@Observes com.ibm.mcp.languagetools.admin.ws.ActivityStateWsMessage msg) {
        broadcast(msg);
    }

    void onServerEnabledChange(@Observes ServerEnabledChangeEvent event) {
        LOG.infof("WebSocket: Server enabled changed: %s -> %s (broadcasting to %d clients)",
                event.serverId(), event.enabled(), sessions.size());
        ServerEnabledChangedWsMessage msg = new ServerEnabledChangedWsMessage(
                event.serverId(), event.enabled());
        broadcast(msg);
    }

    /**
     * Send recent operation history to a newly connected session.
     */
    private void sendOperationHistory(Session session) {
        try {
            for (var op : operationTracker.getRecentOperations(100)) {
                OperationUpdateWsMessage msg = new OperationUpdateWsMessage("COMPLETED", op);
                sendToSession(session, msg);
            }
            LOG.debugf("Operation history sent to session: %s", session.getId());
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send operation history to session: %s", session.getId());
        }
    }

    /**
     * Broadcast message to all connected sessions.
     */
    private void broadcast(Object message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize message: %s", message);
            return;
        }

        sessions.forEach(session -> {
            if (session.isOpen()) {
                // Use async send with callback for backpressure handling
                session.getAsyncRemote().sendText(json, result -> {
                    if (!result.isOK()) {
                        LOG.warnf("Failed to send to session %s: %s",
                                session.getId(), result.getException().getMessage());

                        // Close slow clients that can't keep up
                        try {
                            session.close(new CloseReason(
                                    CloseReason.CloseCodes.TRY_AGAIN_LATER,
                                    "Client too slow"
                            ));
                        } catch (IOException e) {
                            // Session already closed
                        }
                        sessions.remove(session);
                    }
                });
            } else {
                sessions.remove(session);
            }
        });
    }

    /**
     * Send message to a specific session.
     */
    private void sendToSession(Session session, Object message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            session.getAsyncRemote().sendText(json);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send message to session: %s", session.getId());
        }
    }

    private List<WorkspaceDTO> getCurrentWorkspaces() {
        return application.getWorkspaces()
                .stream()
                .map(WorkspaceDTO::fromWorkspace)
                .toList();
    }

    private List<McpClientDTO> getCurrentMcpClients() {
        return McpClientDTO.fromConnections(connectionManager);
    }

    private static DapTraceWsMessage toDapTraceWsMessage(TraceMessage trace) {
        String contextId = trace.contextId();
        String serverId;
        String sessionId;
        int hashIndex = contextId.indexOf('#');
        if (hashIndex >= 0) {
            serverId = contextId.substring(0, hashIndex);
            sessionId = contextId.substring(hashIndex + 1);
        } else {
            serverId = contextId;
            sessionId = null;
        }
        return new DapTraceWsMessage(
                trace.workspaceUri(), serverId, sessionId,
                trace.content(), trace.messageType());
    }
}
