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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Java Debug Server implementation that uses JDTLS for embedded debugging.
 *
 * <p>This server doesn't launch an external process. Instead, it:</p>
 * <ol>
 *   <li>Calls JDTLS commands to resolve classpath, java executable, etc.</li>
 *   <li>Calls vscode.java.startDebugSession to load the java-debug bundle in JDTLS</li>
 *   <li>Connects to the returned port</li>
 * </ol>
 */
public class JavaDebugServer extends DapServer {

    private static final Logger LOG = Logger.getLogger(JavaDebugServer.class);

    private static final String JDTLS_SERVER_ID = "jdtls";

    // JDTLS commands
    private static final String CMD_VALIDATE_LAUNCH_CONFIG = "vscode.java.validateLaunchConfig";
    private static final String CMD_BUILD_WORKSPACE = "vscode.java.buildWorkspace";
    private static final String CMD_RESOLVE_CLASSPATH = "vscode.java.resolveClasspath";
    private static final String CMD_RESOLVE_JAVA_EXECUTABLE = "vscode.java.resolveJavaExecutable";

    public JavaDebugServer(DapSession session, DapServerConfig config, Workspace workspace) {
        super(session, config, workspace);
    }

    /**
     * Override to create JavaDebugClient instead of base DapClient.
     */
    @Override
    protected DapClient createDapClient() {
        return new JavaDebugClient();
    }

    /**
     * Override to create child JavaDebugClient.
     */
    @Override
    public DapClient createDapClient(DapClient parentClient) {
        return new JavaDebugClient(parentClient);
    }

    /**
     * Override enrichLaunchConfiguration to add Java-specific resolution.
     * This is called by DapSession.launch() before connecting to the debug adapter.
     */
    @Override
    public CompletableFuture<Map<String, Object>> enrichLaunchConfiguration(
            Map<String, Object> launchConfig,
            String sessionId,
            ProgressMonitor progressMonitor) {

        String request = (String) launchConfig.get("request");
        String cwd = (String) launchConfig.get("cwd");
        LOG.infof("Enriching configuration for Java, request type: %s, cwd: %s", request, cwd);
        getWorkspace().flushFileWatcher();

        progressMonitor.reportProgress("Ensuring Java language server (JDT.LS) is ready...");
        addTrace("Waiting for JDT.LS to be ready...");

        return ensureModuleSetupForDebug(cwd, progressMonitor, sessionId)
                .thenCompose(v -> {
                    progressMonitor.reportProgress("Resolving launch configuration...");
                    addTrace("Resolving launch configuration...");
                    return resolveLaunchConfiguration(launchConfig, sessionId);
                })
                .thenCompose(enrichedConfig -> {
                    progressMonitor.reportProgress("Connecting to debug adapter...");
                    addTrace("Connecting to debug adapter...");
                    return startEmbeddedDebugSession(getConfig().getLaunchMethod(), enrichedConfig);
                });
    }

    private CompletableFuture<Void> ensureModuleSetupForDebug(String cwd,
                                                               ProgressMonitor progressMonitor,
                                                               String sessionId) {
        if (cwd == null) {
            return CompletableFuture.completedFuture(null);
        }
        // Start JDTLS first (install + initialize), then wait for indexing with trace forwarding
        return getWorkspace().ensureLspServerStarted(JDTLS_SERVER_ID, ProgressMonitor.messageOnly(progressMonitor))
                .thenCompose(jdtls -> {
                    // Register trace forwarder AFTER server is started (so it exists)
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

    /**
     * Resolve launch configuration by calling JDTLS commands.
     * This enriches the launch config with classPaths, modulePaths, javaExec, etc.
     *
     * @param launchConfig The initial launch configuration
     * @param sessionId The session ID for tracing
     * @return Enriched launch configuration
     */
    public CompletableFuture<Map<String, Object>> resolveLaunchConfiguration(
            Map<String, Object> launchConfig,
            String sessionId) {

        String request = (String) launchConfig.get("request");
        String workspaceRootUri = getWorkspace().getNormalizedUri();

        LOG.infof("Resolving configuration for request type: %s", request);

        // Handle "attach" request type
        if ("attach".equals(request)) {
            return resolveAttachConfiguration(launchConfig, sessionId);
        }

        // Handle "launch" request type
        if (!"launch".equals(request)) {
            String error = String.format("Request type \"%s\" is not supported. Only \"launch\" and \"attach\" are supported.", request);
            LOG.error(error);
            return CompletableFuture.failedFuture(new IllegalArgumentException(error));
        }

        String mainClass = (String) launchConfig.get("mainClass");
        String projectName = (String) launchConfig.get("projectName");

        LOG.infof("Resolving launch configuration for mainClass=%s, projectName=%s", mainClass, projectName);

        // If mainClass is missing, return config as-is (for test configurations)
        if (mainClass == null || mainClass.isEmpty()) {
            LOG.infof("No mainClass provided, skipping JDTLS resolution");
            return CompletableFuture.completedFuture(launchConfig);
        }

        // Step 1: Validate launch config
        return validateLaunchConfig(workspaceRootUri, mainClass, projectName, sessionId)
                .thenCompose(validation -> {
                    // Step 2: Build workspace if needed
                    return buildWorkspace(mainClass, sessionId);
                })
                .thenCompose(buildResult -> {
                    // Step 3: Resolve classpath
                    return resolveClasspath(mainClass, projectName, sessionId);
                })
                .thenCompose(classpaths -> {
                    // Step 4: Resolve java executable
                    return resolveJavaExecutable(mainClass, projectName, sessionId)
                            .thenApply(javaExec -> {
                                // Enrich launch config
                                launchConfig.put("modulePaths", classpaths.get(0)); // modulePaths
                                launchConfig.put("classPaths", classpaths.get(1));  // classPaths
                                launchConfig.put("javaExec", javaExec);

                                LOG.infof("Launch configuration resolved: classPaths=%d entries, javaExec=%s",
                                        ((List<?>) classpaths.get(1)).size(), javaExec);

                                return launchConfig;
                            });
                })
                .exceptionally(ex -> {
                    String error = String.format("Failed to resolve launch configuration: %s", ex.getMessage());
                    LOG.error(error, ex);
                    addTrace(String.format("ERROR resolving launch config: %s", ex.getMessage()));
                    throw new RuntimeException(error, ex);
                });
    }

    /**
     * Resolve attach configuration.
     * Validates that either hostName/port or processId is configured.
     *
     * @param launchConfig The initial attach configuration
     * @param sessionId The session ID for tracing
     * @return The attach configuration (validated but not enriched)
     */
    private CompletableFuture<Map<String, Object>> resolveAttachConfiguration(
            Map<String, Object> launchConfig,
            String sessionId) {

        String hostName = (String) launchConfig.get("hostName");
        Object portObj = launchConfig.get("port");
        Object processIdObj = launchConfig.get("processId");

        LOG.infof("Resolving attach configuration: hostName=%s, port=%s, processId=%s",
                hostName, portObj, processIdObj);

        // Check if hostName and port are configured
        if (hostName != null && !hostName.isEmpty() && portObj != null) {
            // Convert port to integer if needed
            int port;
            if (portObj instanceof Number) {
                port = ((Number) portObj).intValue();
            } else if (portObj instanceof String) {
                try {
                    port = Integer.parseInt((String) portObj);
                } catch (NumberFormatException e) {
                    String error = String.format("Invalid port value: %s", portObj);
                    LOG.error(error);
                    return CompletableFuture.failedFuture(new IllegalArgumentException(error));
                }
            } else {
                String error = String.format("Port must be a number, got: %s", portObj.getClass().getSimpleName());
                LOG.error(error);
                return CompletableFuture.failedFuture(new IllegalArgumentException(error));
            }

            launchConfig.put("port", port);
            launchConfig.remove("processId"); // Ensure processId is not set
            LOG.infof("Attach configuration validated: hostName=%s, port=%d", hostName, port);

            addTrace(String.format("Attach config validated: hostName=%s, port=%d", hostName, port));

            return CompletableFuture.completedFuture(launchConfig);
        }

        // Check if processId is configured (not supported in this implementation)
        if (processIdObj != null) {
            String error = "Attach by processId is not yet supported. Please use hostName and port.";
            LOG.error(error);
            addTrace("ERROR: " + error);
            return CompletableFuture.failedFuture(new UnsupportedOperationException(error));
        }

        // Neither hostName/port nor processId is configured
        String error = "Please specify the hostName/port directly, or provide the processId of the remote debuggee in the launch configuration.";
        LOG.error(error);
        addTrace("ERROR: " + error);
        return CompletableFuture.failedFuture(new IllegalArgumentException(error));
    }

    // ===== Private helper methods for JDTLS commands =====

    private CompletableFuture<Object> validateLaunchConfig(
            String workspaceRootUri,
            String mainClass,
            String projectName,
            String sessionId) {

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

    private CompletableFuture<Object> buildWorkspace(String mainClass, String sessionId) {
        return getWorkspace().buildWorkspace()
                .thenApply(result -> {
                    LOG.debugf("Build workspace result: %s", result);
                    return (Object) result;
                });
    }

    private CompletableFuture<List<List<String>>> resolveClasspath(
            String mainClass,
            String projectName,
            String sessionId) {

        // Use Arrays.asList instead of List.of because List.of doesn't allow null values
        List<Object> args = Arrays.asList(mainClass, projectName, null);

        return routeRequest(CMD_RESOLVE_CLASSPATH, args)
                .handle((result, ex) -> {
                    if (ex != null) {
                        String error = String.format("Error calling %s: %s", CMD_RESOLVE_CLASSPATH, ex.getMessage());
                        LOG.error(error, ex);
                        throw new RuntimeException(error, ex);
                    }
                    @SuppressWarnings("unchecked")
                    List<List<String>> classpaths = (List<List<String>>) result;
                    LOG.debugf("Resolved classpath: modulePaths=%d, classPaths=%d",
                            classpaths.get(0).size(), classpaths.get(1).size());
                    return classpaths;
                });
    }

    private CompletableFuture<String> resolveJavaExecutable(
            String mainClass,
            String projectName,
            String sessionId) {

        // Use Arrays.asList instead of List.of because List.of doesn't allow null values
        List<Object> args = Arrays.asList(mainClass, projectName);

        return routeRequest(CMD_RESOLVE_JAVA_EXECUTABLE, args)
                .handle((result, ex) -> {
                    if (ex != null) {
                        String error = String.format("Error calling %s: %s", CMD_RESOLVE_JAVA_EXECUTABLE, ex.getMessage());
                        LOG.error(error, ex);
                        throw new RuntimeException(error, ex);
                    }
                    String javaExec = (String) result;
                    LOG.debugf("Resolved java executable: %s", javaExec);
                    return javaExec;
                });
    }
}
