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
package org.eclipse.mcp.ade.extensions.jdtls.dap;

import org.eclipse.mcp.ade.dap.client.DapClient;
import org.eclipse.mcp.ade.dap.server.DapServer;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.eclipse.mcp.ade.dap.session.DapSession;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.workspace.Workspace;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Java Debug Server implementation that uses JDTLS for embedded debugging.
 *
 * <p>This server doesn't launch an external process. Instead, it delegates to the
 * base {@link DapServer} which handles LSP server preparation (via contributes)
 * and declarative resolve steps from server.json.</p>
 */
public class JavaDebugServer extends DapServer {

    private static final Logger LOG = Logger.getLogger(JavaDebugServer.class);

    public JavaDebugServer(DapSession session, DapServerConfig config, Workspace workspace) {
        super(session, config, workspace);
    }

    @Override
    protected DapClient createDapClient() {
        return new JavaDebugClient();
    }

    @Override
    public DapClient createDapClient(DapClient parentClient) {
        return new JavaDebugClient(parentClient);
    }

    @Override
    public CompletableFuture<Map<String, Object>> enrichLaunchConfiguration(
            Map<String, Object> launchConfig,
            String sessionId,
            ProgressMonitor progressMonitor) {

        String request = (String) launchConfig.get("request");
        LOG.infof("Enriching configuration for Java, request type: %s", request);

        if ("attach".equals(request)) {
            return resolveAttachConfiguration(launchConfig)
                    .thenCompose(config ->
                            super.enrichLaunchConfiguration(config, sessionId, progressMonitor));
        }

        if (!"launch".equals(request)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    String.format("Request type \"%s\" is not supported. Only \"launch\" and \"attach\" are supported.", request)));
        }

        String mainClass = (String) launchConfig.get("mainClass");
        if (mainClass == null || mainClass.isEmpty()) {
            LOG.infof("No mainClass provided, skipping JDTLS resolution");
            return startEmbeddedDebugSession(getConfig().getLaunchMethod(), launchConfig);
        }

        launchConfig.putIfAbsent("projectName", null);

        return super.enrichLaunchConfiguration(launchConfig, sessionId, progressMonitor);
    }

    private CompletableFuture<Map<String, Object>> resolveAttachConfiguration(
            Map<String, Object> launchConfig) {

        String hostName = (String) launchConfig.get("hostName");
        Object portObj = launchConfig.get("port");
        Object processIdObj = launchConfig.get("processId");

        LOG.infof("Resolving attach configuration: hostName=%s, port=%s, processId=%s",
                hostName, portObj, processIdObj);

        if (hostName != null && !hostName.isEmpty() && portObj != null) {
            int port;
            if (portObj instanceof Number) {
                port = ((Number) portObj).intValue();
            } else if (portObj instanceof String) {
                try {
                    port = Integer.parseInt((String) portObj);
                } catch (NumberFormatException e) {
                    return CompletableFuture.failedFuture(
                            new IllegalArgumentException(String.format("Invalid port value: %s", portObj)));
                }
            } else {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException(String.format("Port must be a number, got: %s", portObj.getClass().getSimpleName())));
            }

            launchConfig.put("port", port);
            launchConfig.remove("processId");
            LOG.infof("Attach configuration validated: hostName=%s, port=%d", hostName, port);
            addTrace(String.format("Attach config validated: hostName=%s, port=%d", hostName, port));
            return CompletableFuture.completedFuture(launchConfig);
        }

        if (processIdObj != null) {
            String error = "Attach by processId is not yet supported. Please use hostName and port.";
            addTrace("ERROR: " + error);
            return CompletableFuture.failedFuture(new UnsupportedOperationException(error));
        }

        String error = "Please specify the hostName/port directly, or provide the processId of the remote debuggee in the launch configuration.";
        addTrace("ERROR: " + error);
        return CompletableFuture.failedFuture(new IllegalArgumentException(error));
    }
}
