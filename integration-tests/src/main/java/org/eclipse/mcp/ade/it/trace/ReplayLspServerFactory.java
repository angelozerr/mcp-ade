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
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.eclipse.mcp.ade.lsp.server.LspServerCreateParams;
import org.eclipse.mcp.ade.lsp.server.LspServerFactory;
import org.eclipse.mcp.ade.workspace.Workspace;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.adapters.LocationLinkListAdapter;
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic SPI factory that creates {@link ReplayLspServerBridge} instances
 * backed by registered {@link LspTraceData}.
 * <p>
 * Trace data is registered per server ID before test execution, and cleared afterwards.
 * The factory matches any server configuration whose ID has registered trace data.
 * <p>
 * Registered via {@code META-INF/services/org.eclipse.mcp.ade.lsp.server.LspServerFactory}.
 */
public class ReplayLspServerFactory implements LspServerFactory {

    public static final Type LOCATION_EITHER_TYPE =
            new TypeToken<Either<List<? extends Location>, List<? extends LocationLink>>>() {}.getType();

    private static final Gson LSP4J_GSON = new MessageJsonHandler(new HashMap<>())
            .getDefaultGsonBuilder()
            .registerTypeAdapterFactory(new TypeAdapterFactory() {
                private final LocationLinkListAdapter delegate = new LocationLinkListAdapter();

                @Override
                public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                    if (type.getType().equals(LOCATION_EITHER_TYPE)) {
                        return delegate.create(gson, type);
                    }
                    return null;
                }
            })
            .create();

    /**
     * Returns a Gson instance that handles all LSP4J types including
     * {@code Either<List<Location>, List<LocationLink>>} via {@link LocationLinkListAdapter}.
     */
    public static Gson getLsp4jGson() {
        return LSP4J_GSON;
    }

    private static final Map<String, LspTraceData> replayData = new ConcurrentHashMap<>();

    /**
     * Register LSP trace data for a given server ID.
     *
     * @param serverId the server ID (e.g., "jdtls", derived from the trace file name)
     * @param data     the parsed LSP trace data
     */
    public static void register(String serverId, LspTraceData data) {
        replayData.put(serverId, data);
    }

    /**
     * Clear all registered trace data.
     * Should be called after each test to avoid leaking state.
     */
    public static void clear() {
        replayData.clear();
    }

    public static LspTraceData getData(String serverId) {
        return replayData.get(serverId);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code null} — this factory uses {@link #canHandle} logic instead of a fixed server ID
     */
    @Override
    public String getServerId() {
        return null;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns {@code true} if trace data has been registered for the config's server ID.
     */
    @Override
    public boolean canHandle(LspServerConfig config, Workspace workspace) {
        return config != null && replayData.containsKey(config.getServerId());
    }

    /**
     * Create a {@link ReplayLspServerBridge} using the registered trace data.
     *
     * @param params the server creation parameters (config + workspace)
     * @return the replay LSP server bridge instance
     */
    @Override
    public LspServer createServer(LspServerCreateParams params) {
        LspTraceData data = replayData.get(params.getConfig().getServerId());
        return new ReplayLspServerBridge(params.getConfig(), params.getWorkspace(), data);
    }
}
