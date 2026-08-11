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
package com.ibm.mcp.languagetools.lsp.client;

import com.ibm.mcp.languagetools.server.ServerRequestRouter;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Endpoint;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Generic LSP client implementation with support for capability registration, bindRequest and bindNotification routing.
 * Extends {@link ServerRequestRouter} to handle bindRequest and bindNotification routing declared in server.json.
 * Implements Endpoint to handle custom requests.
 *
 * bindRequest: defaults to "executeCommand" mode (workspace/executeCommand)
 * bindNotification: defaults to "direct" mode (direct method call)
 */
public class GenericLanguageClient extends ServerRequestRouter implements LanguageClient, Endpoint {

    private static final Logger LOG = Logger.getLogger(GenericLanguageClient.class);

    protected final LspServer lspServer;

    private final Map<String, CompletableFuture<List<Diagnostic>>> diagnosticsFutures = new ConcurrentHashMap<>();

    public GenericLanguageClient(LspServer lspServer) {
        super(lspServer.getConfig(), lspServer.getWorkspace());
        this.lspServer = lspServer;
    }

    @Override
    public void telemetryEvent(Object object) {
        // Ignore telemetry for now
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        LOG.debugf("Diagnostics published for: %s", diagnostics.getUri());
        lspServer.getDiagnosticsCache().put(diagnostics.getUri(), diagnostics.getDiagnostics());
        CompletableFuture<List<Diagnostic>> future = diagnosticsFutures.remove(diagnostics.getUri());
        if (future != null) {
            future.complete(diagnostics.getDiagnostics());
        }
    }

    public CompletableFuture<List<Diagnostic>> waitForDiagnostics(String uri, long timeoutMs) {
        CompletableFuture<List<Diagnostic>> future = new CompletableFuture<>();
        diagnosticsFutures.put(uri, future);
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    diagnosticsFutures.remove(uri);
                    List<Diagnostic> cached = lspServer.getDiagnosticsCache().get(uri);
                    return cached != null ? cached : Collections.emptyList();
                });
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        LOG.infof("%s message: %s", lspServer.getConfig().getServerId(), messageParams.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        LOG.infof("%s log: %s", lspServer.getConfig().getServerId(), message.getMessage());
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
        LOG.infof("[%s] Registering capabilities", lspServer.getConfig().getServerId());
        lspServer.getClientFeatures().registerCapability(params);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams params) {
        LOG.infof("[%s] Unregistering capabilities", lspServer.getConfig().getServerId());
        lspServer.getClientFeatures().unregisterCapability(params);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams configurationParams) {
        var workspaceConfig = lspServer.getWorkspace().getIdeConfiguration();
        return CompletableFuture.completedFuture(workspaceConfig.find(configurationParams.getItems()));
    }

    // -- Endpoint implementation (delegates to ServerRequestRouter) --

    @Override
    public CompletableFuture<?> request(String method, Object parameter) {
        return routeRequest(method, parameter);
    }

    @Override
    public void notify(String method, Object parameter) {
        routeNotification(method, parameter);
    }
}
