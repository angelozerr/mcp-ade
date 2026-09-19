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
package org.eclipse.mcp.ade.mcp;

import io.quarkiverse.mcp.server.InitialRequest;
import io.quarkiverse.mcp.server.McpConnection;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the currently connected MCP client(s).
 *
 * <p>For Streamable HTTP transport, connections are transient (created and
 * removed per request). This tracker captures client info from MCP traffic
 * so that clients remain visible in the admin UI even when their connection
 * objects no longer exist in the ConnectionManager.</p>
 */
@ApplicationScoped
public class McpClientTracker {

    private static final Logger LOG = Logger.getLogger(McpClientTracker.class);
    private static final String AUTO_INIT_IMPL_NAME = "quarkus.mcp.http.streamable.dummy";
    private static final Duration CLIENT_TTL = Duration.ofMinutes(30);

    private volatile String currentClientName = "No client connected";
    private volatile String currentConnectionId = null;

    private final ConcurrentHashMap<String, TrackedClient> trackedClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> connectionToClientKey = new ConcurrentHashMap<>();

    public void trackConnection(McpConnection connection) {
        String id = connection.id();
        if (id.equals(currentConnectionId)) {
            return;
        }
        this.currentConnectionId = id;

        InitialRequest req = connection.initialRequest();
        String name = "HTTP Client";
        String version = null;
        String protocolVersion = null;
        boolean autoInit = true;

        if (req != null) {
            autoInit = req.autoInitialized();
            if (req.implementation() != null) {
                name = req.implementation().name();
                version = req.implementation().version();
            }
            if (req.protocolVersion() != null) {
                protocolVersion = req.protocolVersion().toString();
            }
        }

        if (autoInit && AUTO_INIT_IMPL_NAME.equals(name)) {
            name = "HTTP Client";
        }

        this.currentClientName = version != null ? name + " " + version : name;

        String key = version != null ? name + "/" + version : name;
        final String n = name;
        final String v = version;
        final String pv = protocolVersion;
        final boolean ai = autoInit;
        trackedClients.compute(key, (k, existing) -> {
            if (existing != null) {
                existing.lastSeen = Instant.now();
                existing.lastConnectionId = id;
                return existing;
            }
            return new TrackedClient(k, n, v, pv, ai, id);
        });

        connectionToClientKey.put(id, key);
    }

    /**
     * Resolve a transient connectionId to the stable client key.
     */
    public String getClientKey(String connectionId) {
        return connectionToClientKey.getOrDefault(connectionId, connectionId);
    }

    public Collection<TrackedClient> getTrackedClients() {
        Instant cutoff = Instant.now().minus(CLIENT_TTL);
        trackedClients.entrySet().removeIf(e -> e.getValue().lastSeen.isBefore(cutoff));
        return trackedClients.values();
    }

    public boolean isActiveClient(String clientName) {
        Instant cutoff = Instant.now().minus(CLIENT_TTL);
        for (TrackedClient tc : trackedClients.values()) {
            if (tc.getDisplayName().equals(clientName) && !tc.lastSeen.isBefore(cutoff)) {
                return true;
            }
        }
        return false;
    }

    public String getCurrentClientName() {
        return currentClientName;
    }

    public String getCurrentConnectionId() {
        return currentConnectionId;
    }

    public static class TrackedClient {
        public final String clientKey;
        public final String name;
        public final String version;
        public final String protocolVersion;
        public final boolean autoInitialized;
        public final Instant firstSeen;
        public volatile Instant lastSeen;
        public volatile String lastConnectionId;

        TrackedClient(String clientKey, String name, String version, String protocolVersion,
                      boolean autoInitialized, String connectionId) {
            this.clientKey = clientKey;
            this.name = name;
            this.version = version;
            this.protocolVersion = protocolVersion;
            this.autoInitialized = autoInitialized;
            this.firstSeen = Instant.now();
            this.lastSeen = this.firstSeen;
            this.lastConnectionId = connectionId;
        }

        public String getDisplayName() {
            return version != null ? name + " " + version : name;
        }
    }
}
