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
import org.eclipse.mcp.ade.extensions.jdtls.lsp.JdtLsServer;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.workspace.Workspace;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Java Debug Server implementation that uses JDTLS for embedded debugging.
 *
 * <p>This server doesn't launch an external process. Instead, it:</p>
 * <ol>
 *   <li>Ensures JDTLS is started and ready (with fast-mode module setup)</li>
 *   <li>Validates the launch config and builds the workspace</li>
 *   <li>Delegates to declarative resolve steps (classpath, java executable) from server.json</li>
 *   <li>Starts the embedded debug session via vscode.java.startDebugSession</li>
 * </ol>
 */
public class JavaDebugServer extends DapServer {

    private static final Logger LOG = Logger.getLogger(JavaDebugServer.class);

    private static final String JDTLS_SERVER_ID = "jdtls";
    private static final String CMD_VALIDATE_LAUNCH_CONFIG = "vscode.java.validateLaunchConfig";

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
        String cwd = (String) launchConfig.get("cwd");
        LOG.infof("Enriching configuration for Java, request type: %s, cwd: %s", request, cwd);

        progressMonitor.reportProgress("Ensuring Java language server (JDT.LS) is ready...");
        addTrace("Waiting for JDT.LS to be ready...");

        return ensureModuleSetupForDebug(cwd, progressMonitor, sessionId)
                .thenCompose(v -> {
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

                    // Ensure projectName is present for declarative resolve steps (may be null)
                    launchConfig.putIfAbsent("projectName", null);

                    String workspaceRootUri = getWorkspace().getNormalizedUri();
                    String projectName = (String) launchConfig.get("projectName");

                    progressMonitor.reportProgress("Validating launch configuration...");
                    return validateLaunchConfig(workspaceRootUri, mainClass, projectName)
                            .thenCompose(v2 -> {
                                progressMonitor.reportProgress("Building workspace...");
                                return buildWorkspace();
                            })
                            .thenCompose(v2 -> {
                                progressMonitor.reportProgress("Resolving launch configuration...");
                                addTrace("Resolving launch configuration...");
                                // Declarative resolve steps (classpath, javaExec) + startEmbeddedDebugSession
                                return super.enrichLaunchConfiguration(launchConfig, sessionId, progressMonitor);
                            });
                })
                .exceptionally(ex -> {
                    String error = String.format("Failed to resolve launch configuration: %s", ex.getMessage());
                    LOG.error(error, ex);
                    addTrace(String.format("ERROR resolving launch config: %s", ex.getMessage()));
                    throw new RuntimeException(error, ex);
                });
    }

    private CompletableFuture<Void> ensureModuleSetupForDebug(String cwd,
                                                               ProgressMonitor progressMonitor,
                                                               String sessionId) {
        if (cwd == null) {
            return CompletableFuture.completedFuture(null);
        }
        return getWorkspace().ensureLspServerStarted(JDTLS_SERVER_ID, ProgressMonitor.messageOnly(progressMonitor))
                .thenCompose(jdtls -> {
                    StatusChangeListener traceForwarder = (oldStatus, newStatus) -> {
                        String msg = jdtls.getStatusMessage();
                        if (msg != null) {
                            progressMonitor.reportProgress(msg);
                            addTrace("[JDT.LS] " + msg);
                        }
                    };
                    jdtls.addStatusChangeListener(traceForwarder);

                    return jdtls.waitForReady()
                            .whenComplete((v, ex) -> jdtls.removeStatusChangeListener(traceForwarder))
                            .thenApply(v -> jdtls);
                })
                .thenCompose(jdtls -> {
                    if (jdtls instanceof JdtLsServer j) {
                        progressMonitor.reportProgress("JDT.LS ready, setting up module...");
                        addTrace("JDT.LS ready, setting up module...");
                        LOG.infof("Triggering module setup for debug CWD: %s", cwd);
                        return j.ensureModuleSetupIfFastMode(cwd)
                                .thenCompose(v -> j.waitForPendingFileWatcherModuleSetups());
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .exceptionally(ex -> {
                    LOG.warnf(ex, "Module setup for debug CWD failed, proceeding anyway");
                    return null;
                });
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

    private CompletableFuture<Object> validateLaunchConfig(
            String workspaceRootUri,
            String mainClass,
            String projectName) {

        List<Object> args = new ArrayList<>();
        args.add(workspaceRootUri);
        args.add(mainClass);
        args.add(projectName);
        args.add(false);

        return routeRequest(CMD_VALIDATE_LAUNCH_CONFIG, args)
                .handle((result, ex) -> {
                    if (ex != null) {
                        String error = String.format("Error calling %s: %s", CMD_VALIDATE_LAUNCH_CONFIG, ex.getMessage());
                        LOG.error(error, ex);
                        throw new RuntimeException(error, ex);
                    }
                    LOG.debugf("Launch config validation result: %s", result);
                    return result;
                });
    }

    private CompletableFuture<Object> buildWorkspace() {
        return getWorkspace().buildWorkspace()
                .thenApply(result -> {
                    LOG.debugf("Build workspace result: %s", result);
                    return (Object) result;
                });
    }
}
