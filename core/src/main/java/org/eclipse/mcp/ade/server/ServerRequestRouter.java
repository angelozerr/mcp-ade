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
package org.eclipse.mcp.ade.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.lsp.server.LspServer;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.workspace.Workspace;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Routes requests and notifications between servers using the
 * {@code contributes.*.bindRequest} and {@code contributes.*.bindNotification}
 * declarations from {@code server.json}.
 *
 * <p>This mechanism allows any server (LSP or DAP) to delegate calls to another
 * LSP server. For example, the java-debug DAP server routes
 * {@code vscode.java.startDebugSession} to the JDTLS language server, and
 * the Qute language server routes {@code qute/template/project} to JDTLS.</p>
 *
 * <p>Two routing modes are supported:</p>
 * <ul>
 *   <li>{@link BindMode#EXECUTE_COMMAND} (default for bindRequest):
 *       wraps the call as an LSP {@code workspace/executeCommand}</li>
 *   <li>{@link BindMode#DIRECT}: sends a raw JSON-RPC request to the target server</li>
 * </ul>
 *
 * @see ServerBase
 */
public class ServerRequestRouter {

    private static final Logger LOG = Logger.getLogger(ServerRequestRouter.class);

    // JSON field names
    private static final String BIND_REQUEST = "bindRequest";
    private static final String BIND_NOTIFICATION = "bindNotification";
    private static final String MODE = "mode";
    private static final String METHODS = "methods";
    private static final String METHOD = "method";
    private static final String TARGET_METHOD = "targetMethod";

    // Bind mode enum
    public enum BindMode {
        EXECUTE_COMMAND("executeCommand"),
        DIRECT("direct");

        private final String value;

        BindMode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static BindMode fromString(String mode) {
            for (BindMode m : values()) {
                if (m.value.equals(mode)) {
                    return m;
                }
            }
            return EXECUTE_COMMAND; // Default fallback
        }
    }

    private record BindInfo(String targetServerId, String targetMethod, BindMode mode) {
    }

    private final ServerConfigBase config;
    private final Workspace workspace;

    /**
     * Creates a request router for the given server configuration and workspace.
     */
    public ServerRequestRouter(ServerConfigBase config, Workspace workspace) {
        this.config = config;
        this.workspace = workspace;
    }

    /**
     * Returns the server configuration.
     */
    protected ServerConfigBase getConfig() {
        return config;
    }

    /**
     * Returns the workspace this router operates in.
     */
    protected Workspace getWorkspace() {
        return workspace;
    }

    /**
     * Route a request to the target server declared in {@code contributes.*.bindRequest}.
     *
     * <p>Looks up the target server and routing mode from the server's
     * {@code contributes} configuration, ensures the target LSP server is
     * running, then forwards the call.</p>
     *
     * @param method request method (e.g., "vscode.java.startDebugSession")
     * @param params request parameters
     * @return CompletableFuture with the response from the target server
     */
    public CompletableFuture<?> routeRequest(String method, Object params) {
        String serverId = config.getServerId();
        LOG.infof("[%s] ServerRequestRouter.routeRequest() called for: %s", serverId, method);

        // Check if this is a bindRequest declared in server.json
        BindInfo bindInfo = findBindInfo(method, BIND_REQUEST, BindMode.EXECUTE_COMMAND);

        if (bindInfo != null) {
            // Target server ID (the one that will handle the custom request)
            String targetServerId = bindInfo.targetServerId;
            // Target method name (may differ from source method)
            String targetMethod = bindInfo.targetMethod();
            // Routing mode (EXECUTE_COMMAND or DIRECT)
            BindMode bindMode = bindInfo.mode();

            LOG.infof("[%s] Routing bindRequest %s to server %s as %s (mode: %s)",
                serverId, method, targetServerId, targetMethod, bindMode.getValue());

            // Bind requests are not monitored (called by LSP4J framework, no way to pass ProgressMonitor)
            // Use none() - acceptable since bind requests are not the main user operations
            ProgressMonitor progressMonitor = ProgressMonitor.none();

            // Ensure target server is started (handles external instances, installation, etc.)
            // Returns the ready LspServer instance

            return workspace.ensureLspServerReady(targetServerId, progressMonitor)
                    .thenCompose(targetServer -> {
                        LOG.debugf("Server %s is ready, routing request %s", targetServerId, targetMethod);

                        onRouteRequestStart(method, params);
                        long startTime = System.nanoTime();

                        CompletableFuture<?> future;
                        if (BindMode.DIRECT == bindMode) {
                            // Direct JSON-RPC request
                            future = targetServer.sendRequest(targetMethod, params);
                        } else {
                            // Default: workspace/executeCommand (for JDT.LS delegate handlers)
                            future = targetServer.sendCommandRequest(targetMethod, params);
                        }

                        return future.whenComplete((result, error) -> {
                            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                            onRouteRequestEnd(method, params, result, error, durationMs);
                        });
                    });
        } else {
            LOG.warnf("[%s] No bindInfo found for method: %s (contributes=%s)",
                serverId, method, config.getContributes());
        }

        // Not a bindRequest - return MethodNotFound
        CompletableFuture<Object> future = new CompletableFuture<>();
        future.completeExceptionally(new ResponseErrorException(
            new ResponseError(ResponseErrorCode.MethodNotFound, "Method not found: " + method, null)));
        return future;
    }

    /**
     * Route a notification to the target server declared in {@code contributes.*.bindNotification}.
     *
     * @param method notification method (e.g., "textDocument/didOpen")
     * @param params notification parameters
     */
    public void routeNotification(String method, Object params) {
        String serverId = config.getServerId();
        LOG.debugf("[%s] ServerRequestRouter.routeNotification() called: %s", serverId, method);

        // Check if this is a bindNotification declared in server.json
        BindInfo bindInfo = findBindInfo(method, BIND_NOTIFICATION, BindMode.DIRECT);

        if (bindInfo != null) {
            // Target server ID (the one that will receive the custom notification)
            String targetServerId = bindInfo.targetServerId;
            // Target method name (may differ from source method)
            String targetMethod = bindInfo.targetMethod();
            // Routing mode (DIRECT by default, or EXECUTE_COMMAND)
            BindMode bindMode = bindInfo.mode();

            LOG.infof("[%s] Routing bindNotification %s to server %s as %s (mode: %s)",
                serverId, method, targetServerId, targetMethod, bindMode.getValue());

            // Look up the target server via workspace
            LspServer targetServer = workspace.getLspServer(targetServerId);
            if (targetServer == null) {
                LOG.warnf("Target server '%s' not found for bindNotification: %s", targetServerId, method);
                return;
            }

            // Wait for target server to be ready before routing (important for JDT.LS)
            targetServer.waitForReady()
                    .thenCompose(v -> {
                        LOG.debugf("Server %s is ready, routing notification %s", targetServerId, targetMethod);

                        if (BindMode.DIRECT.equals(bindMode)) {
                            // Direct JSON-RPC request (notification sent as request)
                            return targetServer.sendRequest(targetMethod, params);
                        } else {
                            // Default: workspace/executeCommand (for JDT.LS delegate handlers)
                            return targetServer.sendCommandRequest(targetMethod, params);
                        }
                    })
                    .exceptionally(ex -> {
                        LOG.errorf(ex, "[%s] Failed to route bindNotification %s",
                            serverId, method);
                        return null;
                    });
        }
    }

    /**
     * Find bind information for a method in server.json contributes section.
     * Works for both bindRequest and bindNotification.
     *
     * @param method The method name to find (e.g., "qute/template/project")
     * @param bindKey The bind type key ("bindRequest" or "bindNotification")
     * @param defaultMode The default mode to use if not specified
     * @return BindInfo if found, null otherwise
     */
    private BindInfo findBindInfo(String method, String bindKey, BindMode defaultMode) {
        if (config.getContributes() == null || config.getContributes().getContributions() == null) {
            LOG.debugf("[%s] No contributes found for findBindInfo(%s, %s)",
                config.getServerId(), method, bindKey);
            return null;
        }

        LOG.debugf("[%s] Searching for %s in %d contributions",
            config.getServerId(), method, config.getContributes().getContributions().size());

        for (Map.Entry<String, JsonElement> entry : config.getContributes().getContributions().entrySet()) {
            String targetServerId = entry.getKey();
            JsonElement contrib = entry.getValue();

            LOG.debugf("[%s] Checking contribution to target server: %s", config.getServerId(), targetServerId);

            if (!contrib.isJsonObject()) {
                LOG.debugf("[%s] Contribution is not JsonObject, skipping", config.getServerId());
                continue;
            }

            JsonObject contribObj = contrib.getAsJsonObject();
            if (!contribObj.has(bindKey)) {
                LOG.debugf("[%s] No %s found in contribution to %s", config.getServerId(), bindKey, targetServerId);
                continue;
            }

            JsonElement bindElement = contribObj.get(bindKey);

            // Format 1: Simple array ["method1", "method2", ...]
            if (bindElement.isJsonArray()) {
                JsonArray bindArray = bindElement.getAsJsonArray();
                for (JsonElement element : bindArray) {
                    BindInfo info = parseBindEntry(element, method, targetServerId, defaultMode);
                    if (info != null) {
                        return info;
                    }
                }
            }
            // Format 2: Object with mode { "mode": "direct", "methods": [...] }
            else if (bindElement.isJsonObject()) {
                JsonObject bindObj = bindElement.getAsJsonObject();
                BindMode mode = defaultMode;
                if (bindObj.has(MODE) && bindObj.get(MODE).isJsonPrimitive()) {
                    mode = BindMode.fromString(bindObj.get(MODE).getAsString());
                }
                if (bindObj.has(METHODS) && bindObj.get(METHODS).isJsonArray()) {
                    JsonArray methods = bindObj.get(METHODS).getAsJsonArray();
                    for (JsonElement element : methods) {
                        BindInfo info = parseBindEntry(element, method, targetServerId, mode);
                        if (info != null) {
                            return info;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Hook called before a routed request is sent. Subclasses override for tracing.
     */
    protected void onRouteRequestStart(String method, Object params) {
    }

    /**
     * Hook called after a routed request completes. Subclasses override for tracing.
     */
    protected void onRouteRequestEnd(String method, Object params, Object result, Throwable error, long durationMs) {
    }

    /**
     * Parse a single bind entry (works for both bindRequest and bindNotification).
     * Supports two formats:
     * - Simple string: "qute/template/project"
     * - Object: { "method": "qute/template/project", "targetMethod": "jdtls/qute/getProject", "mode": "direct" }
     */
    private BindInfo parseBindEntry(JsonElement entry, String sourceMethod, String targetServerId, BindMode defaultMode) {
        // Simple string: "qute/template/project"
        if (entry.isJsonPrimitive() && entry.getAsString().equals(sourceMethod)) {
            return new BindInfo(targetServerId, sourceMethod, defaultMode);
        }
        // Object: { "method": "qute/template/project", "targetMethod": "jdtls/qute/getProject", "mode": "direct" }
        else if (entry.isJsonObject()) {
            JsonObject obj = entry.getAsJsonObject();
            if (obj.has(METHOD) && obj.get(METHOD).isJsonPrimitive()) {
                String method = obj.get(METHOD).getAsString();
                if (method.equals(sourceMethod)) {
                    String targetMethod = sourceMethod; // Default: same name
                    if (obj.has(TARGET_METHOD) && obj.get(TARGET_METHOD).isJsonPrimitive()) {
                        targetMethod = obj.get(TARGET_METHOD).getAsString();
                    }
                    BindMode mode = defaultMode; // Use default from parent
                    if (obj.has(MODE) && obj.get(MODE).isJsonPrimitive()) {
                        mode = BindMode.fromString(obj.get(MODE).getAsString());
                    }
                    return new BindInfo(targetServerId, targetMethod, mode);
                }
            }
        }
        return null;
    }
}
