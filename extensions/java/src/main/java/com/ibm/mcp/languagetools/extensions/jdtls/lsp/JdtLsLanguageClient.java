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
package com.ibm.mcp.languagetools.extensions.jdtls.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.lsp.Contributes;
import com.ibm.mcp.languagetools.lsp.client.GenericLanguageClient;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Language client for JDT.LS with support for java/languageStatus notifications.
 * Extends GenericLanguageClient to inherit bindRequest routing.
 */
public class JdtLsLanguageClient extends GenericLanguageClient {

    private static final Logger LOG = Logger.getLogger(JdtLsLanguageClient.class);

    private final JdtLsServer server;

    public JdtLsLanguageClient(JdtLsServer server) {
        super(server);
        this.server = server;
    }

    @JsonRequest("workspace/executeClientCommand")
    public CompletableFuture<Object> executeClientCommand(ExecuteCommandParams params) {
        String command = params.getCommand();
        LOG.infof("JDT.LS executeClientCommand: %s", command);

        String jdtlsServerId = getConfig().getServerId();

        for (LspServerConfig serverConfig : getWorkspace().getApplication().getLspServerConfigs()) {
            Contributes contributes = serverConfig.getContributes();
            if (contributes == null) {
                continue;
            }
            JsonElement contribution = contributes.getContribution(jdtlsServerId);
            if (contribution == null || !contribution.isJsonObject()) {
                continue;
            }
            if (containsCommand(contribution.getAsJsonObject(), command)) {
                String targetServerId = serverConfig.getServerId();
                LOG.infof("Routing executeClientCommand '%s' to server '%s'", command, targetServerId);
                Object commandArgs = extractCommandArgs(params);
                return getWorkspace().ensureLspServerReady(targetServerId, ProgressMonitor.none())
                        .thenCompose(targetServer -> targetServer.sendRequest(command, commandArgs))
                        .thenApply(result -> (Object) result);
            }
        }

        LOG.debugf("No handler found for client command: %s", command);
        return CompletableFuture.completedFuture(null);
    }

    private static boolean containsCommand(JsonObject contribObj, String command) {
        return containsInBindArray(contribObj, "bindNotification", command)
                || containsInBindArray(contribObj, "bindRequest", command);
    }

    private static boolean containsInBindArray(JsonObject contribObj, String bindKey, String command) {
        if (!contribObj.has(bindKey)) {
            return false;
        }
        JsonElement bindElement = contribObj.get(bindKey);
        if (!bindElement.isJsonArray()) {
            return false;
        }
        for (JsonElement elem : bindElement.getAsJsonArray()) {
            if (elem.isJsonPrimitive() && command.equals(elem.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static Object extractCommandArgs(ExecuteCommandParams params) {
        List<Object> args = params.getArguments();
        if (args == null || args.isEmpty()) {
            return null;
        }
        return args.size() == 1 ? args.get(0) : args;
    }

    @JsonNotification("language/status")
    public void languageStatus(StatusReport status) {
        LOG.infof("JDT.LS status [%s]: %s", status.getType(), status.getMessage());
    }

    /**
     * StatusReport for language/status notification.
     */
    public static class StatusReport {
        private String type;
        private String message;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }
}
