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
package org.eclipse.mcp.ade.dap.session;

import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.eclipse.mcp.ade.operation.OperationActor;
import org.eclipse.mcp.ade.language.LanguageDocument;
import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.server.ServerBase;
import org.eclipse.mcp.ade.server.ServerStatus;
import org.eclipse.mcp.ade.trace.TraceCollector;
import org.eclipse.mcp.ade.workspace.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple DAP debug sessions across workspaces.
 * <p>
 * Responsibilities:
 * - Create/destroy debug sessions
 * - Track active sessions (sessionId -> DapSession)
 * - Route tool calls to the correct session
 * - Match debug adapters to languages/file types
 */
@ApplicationScoped
public class DapSessionManager {

    private static final Logger LOG = Logger.getLogger(DapSessionManager.class);

    @Inject
    Application application;

    private TraceCollector getTraceCollector() {
        return application.getDapTraceCollector();
    }

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    Event<DapSessionEvent> sessionEvent;

    private final Map<String, DapSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ServerBase.StatusChangeListener> sessionStatusListeners = new ConcurrentHashMap<>();

    /**
     * Create a new debug session for a specific DAP server.
     *
     * @param workspaceUri Workspace URI for context
     * @param dapServerId  DAP server ID (e.g., "debugpy", "vscode-js-debug")
     * @param sessionName  User-friendly name for the session
     * @param createdBy    Who created the session
     * @return Created DapSession
     */
    public DapSession createSession(URI workspaceUri,
                                    String dapServerId,
                                    String sessionName,
                                    OperationActor createdBy) {
        LOG.infof("Creating debug session: workspace=%s, dapServerId=%s, name=%s, createdBy=%s",
                workspaceUri, dapServerId, sessionName, createdBy);

        // Find workspace
        Workspace workspace = application.getOrCreateWorkspace(workspaceUri);
        if (workspace == null) {
            throw new IllegalArgumentException("Workspace not found: " + workspaceUri);
        }

        // Find DAP server config
        DapServerConfig serverConfig = findDapServerById(dapServerId);
        if (serverConfig == null) {
            throw new IllegalArgumentException("DAP server not found: " + dapServerId);
        }

        // Set trace collector for installation support
        String sessionId = UUID.randomUUID().toString();
        if (serverConfig.getTraceCollector() == null) {
            serverConfig.setTraceCollector(getTraceCollector());
        }

        // Determine language from server config (first supported language)
        String language = extractLanguageFromConfig(serverConfig);

        // Create session
        DapSession session = new DapSession(
                sessionId,
                language,
                sessionName,
                createdBy,
                serverConfig,
                workspace
        );

        sessions.put(sessionId, session);
        LOG.infof("Created session %s for %s (%s)", sessionId, language, sessionName);

        // Register status change listener on the DAP server
        ServerBase.StatusChangeListener statusListener = (oldStatus, newStatus) -> {
            LOG.infof("DAP server status changed: session=%s, %s -> %s", sessionId, oldStatus, newStatus);
            fireStateChangedEvent(session, oldStatus, newStatus);
        };
        sessionStatusListeners.put(sessionId, statusListener);
        session.getDapServer().addStatusChangeListener(statusListener);

        // Register session state change listener
        session.setStateChangeCallback(() -> {
            LOG.infof("DAP session state changed: session=%s, new state=%s", sessionId, session.getState());
            fireStateChangedEvent(session, null, null);
        });

        // Fire CDI event for WebSocket notification
        fireCreatedEvent(session);
        // Don't initialize yet - initialization (including installation) happens on first launch
        return session;
    }

    /**
     * List all active debug sessions.
     */
    public List<Map<String, Object>> listSessions() {
        LOG.debugf("Listing %d active debug sessions", sessions.size());

        List<Map<String, Object>> result = new ArrayList<>();
        for (DapSession session : sessions.values()) {
            Map<String, Object> sessionInfo = new HashMap<>();
            sessionInfo.put("sessionId", session.getSessionId());
            sessionInfo.put("serverId", session.getServerConfig().getServerId()); // DAP server ID
            sessionInfo.put("workspaceUri", session.getWorkspace().getNormalizedUri()); // Workspace URI
            sessionInfo.put("language", session.getLanguage());
            sessionInfo.put("sessionName", session.getSessionName());
            sessionInfo.put("state", session.getState().name());
            sessionInfo.put("createdBy", session.getCreatedBy());
            sessionInfo.put("launchedBy", session.getLaunchedBy());
            sessionInfo.put("debugMode", session.isDebugMode());

            // Include timestamps
            if (session.getCreatedAt() != null) {
                sessionInfo.put("createdAt", session.getCreatedAt().toString());
            }
            if (session.getLaunchedAt() != null) {
                sessionInfo.put("launchedAt", session.getLaunchedAt().toString());
            }

            // Include error message if session is in ERROR state
            String errorMessage = session.getErrorMessage();
            if (errorMessage != null) {
                sessionInfo.put("error", errorMessage);
            }

            // Include launch configuration if available
            if (session.getLaunchConfiguration() != null) {
                sessionInfo.put("launchConfiguration", session.getLaunchConfiguration());
            }

            result.add(sessionInfo);
        }
        return result;
    }

    /**
     * Get all active sessions.
     */
    public List<DapSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * Get a session by ID.
     *
     * @throws IllegalArgumentException if no session exists with the given ID
     */
    public DapSession getSession(String sessionId) {
        DapSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Debug session not found: " + sessionId);
        }
        return session;
    }

    /**
     * Get all sessions for a specific workspace.
     */
    public List<DapSession> getSessionsForWorkspace(URI workspaceUri) {
        return sessions.values().stream()
                .filter(session -> session.getWorkspace().getRootUri().equals(workspaceUri))
                .toList();
    }

    /**
     * Close and remove a debug session.
     */
    public CompletableFuture<Map<String, Object>> closeSession(String sessionId) {
        LOG.infof("Closing debug session: %s", sessionId);

        DapSession session = sessions.remove(sessionId);
        if (session == null) {
            return CompletableFuture.completedFuture(Map.of(
                    "success", false,
                    "message", "Session not found: " + sessionId
            ));
        }

        // Remove the status change listener that was registered in createSession()
        ServerBase.StatusChangeListener statusListener = sessionStatusListeners.remove(sessionId);
        if (statusListener != null) {
            session.getDapServer().removeStatusChangeListener(statusListener);
        }

        return session.terminate()
                .thenApply(v -> {
                    LOG.infof("Session closed: %s", sessionId);
                    fireDeletedEvent(session);
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("sessionId", sessionId);
                    result.put("message", "Debug session closed");
                    return result;
                })
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Error closing session %s", sessionId);
                    fireDeletedEvent(session);
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", false);
                    result.put("sessionId", sessionId);
                    result.put("message", "Error closing session: " + ex.getMessage());
                    return result;
                });
    }

    /**
     * List all available debug adapters with their details.
     */
    public List<Map<String, Object>> listDebugAdapters() {
        List<Map<String, Object>> adapters = new ArrayList<>();

        for (DapServerConfig config : application.getDapServerConfigs()) {
            adapters.add(buildAdapterInfo(config));
        }

        LOG.debugf("Available debug adapters: %d", adapters.size());
        return adapters;
    }

    /**
     * List debug adapters suitable for a specific file.
     * Uses LanguageRegistry to detect the language automatically.
     */
    public List<Map<String, Object>> listDebugAdaptersForFile(URI fileUri, Path basePath) {
        List<Map<String, Object>> adapters = new ArrayList<>();

        // Create language document (detects language using LanguageRegistry)
        LanguageDocument document = languageRegistry.createDocument(fileUri);
        String language = document.getLanguageId();

        if (language == null) {
            LOG.warnf("Could not detect language for file: %s", fileUri);
            // Return all adapters if language detection fails
            return listDebugAdapters();
        }

        for (DapServerConfig config : application.getDapServerConfigs()) {
            // Check if this adapter supports the file's language
            if (config.canHandle(fileUri, language, basePath)) {
                adapters.add(buildAdapterInfo(config));
            }
        }

        LOG.debugf("Debug adapters for file %s (language: %s): %d", fileUri, language, adapters.size());
        return adapters;
    }

    private Map<String, Object> buildAdapterInfo(DapServerConfig config) {
        Map<String, Object> adapter = new HashMap<>();
        adapter.put("id", config.getServerId());
        adapter.put("name", config.getName());
        if (config.getUrl() != null) {
            adapter.put("url", config.getUrl());
        }
        adapter.put("enabled", true);  // All configs from getDapServerConfigs are enabled

        // Extract supported languages
        List<String> languages = new ArrayList<>();
        if (config.getDocumentSelector() != null) {
            languages.addAll(config.getDocumentSelector().getLanguages());
        }
        adapter.put("languages", languages);

        return adapter;
    }


    /**
     * Get statistics about active sessions.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Long> stateCount = new HashMap<>();
        for (DapSession session : sessions.values()) {
            String state = session.getState().name();
            stateCount.put(state, stateCount.getOrDefault(state, 0L) + 1);
        }

        return Map.of(
                "totalSessions", sessions.size(),
                "sessionsByState", stateCount,
                "availableAdapters", application.getDapServerConfigs().size()
        );
    }

    // ========== Helper Methods ==========

    private DapServerConfig findDapServerById(String dapServerId) {
        return application.getDapServerConfig(dapServerId);
    }

    /**
     * Extract language from DAP server config (first supported language).
     */
    private String extractLanguageFromConfig(DapServerConfig config) {
        if (config.getDocumentSelector() != null && !config.getDocumentSelector().isEmpty()) {
            List<String> langs = config.getDocumentSelector().getLanguages();
            String lang = langs.isEmpty() ? null : langs.get(0);
            if (lang != null) {
                return lang;
            }
        }
        // Fallback: derive from server ID
        return config.getServerId();
    }

    /**
     * Fire STATE_CHANGED event (with null check).
     */
    private void fireStateChangedEvent(DapSession session, ServerStatus oldStatus, ServerStatus newStatus) {
        if (sessionEvent != null) {
            sessionEvent.fire(DapSessionEvent.stateChanged(session, oldStatus, newStatus));
            LOG.infof("Fired STATE_CHANGED event for session %s: %s -> %s (debugMode=%s)",
                session.getSessionId(), oldStatus, newStatus, session.isDebugMode());
        } else {
            LOG.errorf("sessionEvent is null! Cannot fire STATE_CHANGED event");
        }
    }

    /**
     * Fire CREATED event (with null check).
     */
    private void fireCreatedEvent(DapSession session) {
        if (sessionEvent != null) {
            sessionEvent.fire(DapSessionEvent.created(session));
            LOG.infof("Fired CREATED event for session %s", session.getSessionId());
        } else {
            LOG.errorf("sessionEvent is null! Cannot fire CREATED event");
        }
    }

    /**
     * Fire DELETED event (with null check).
     */
    private void fireDeletedEvent(DapSession session) {
        if (sessionEvent != null) {
            sessionEvent.fire(DapSessionEvent.deleted(session));
            LOG.infof("Fired DELETED event for session %s", session.getSessionId());
        } else {
            LOG.errorf("sessionEvent is null! Cannot fire DELETED event");
        }
    }

    // ========== Cleanup ==========

    /**
     * Shutdown all active sessions (called on application shutdown).
     */
    public CompletableFuture<Void> shutdownAll() {
        LOG.infof("Shutting down all %d debug sessions", sessions.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DapSession session : sessions.values()) {
            futures.add(session.terminate());
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenAccept(v -> {
                    sessions.clear();
                    sessionStatusListeners.clear();
                    LOG.info("All debug sessions shut down");
                });
    }
}
