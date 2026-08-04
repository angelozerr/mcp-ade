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
package com.ibm.mcp.languagetools.extensions.jdtls.dap;

import com.ibm.mcp.languagetools.dap.client.DapClient;
import com.ibm.mcp.languagetools.dap.server.DapServer;
import com.ibm.mcp.languagetools.dap.server.DapServerConfig;
import com.ibm.mcp.languagetools.dap.session.DapSession;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.jboss.logging.Logger;

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

    // JDTLS commands
    private static final String CMD_VALIDATE_LAUNCH_CONFIG = "vscode.java.validateLaunchConfig";
    private static final String CMD_BUILD_WORKSPACE = "vscode.java.buildWorkspace";
    private static final String CMD_RESOLVE_CLASSPATH = "vscode.java.resolveClasspath";
    private static final String CMD_RESOLVE_JAVA_EXECUTABLE = "vscode.java.resolveJavaExecutable";
    private static final String CMD_START_DEBUG_SESSION = "vscode.java.startDebugSession";

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
        return new JavaDebugClient((JavaDebugClient) parentClient);
    }

    @Override
    protected CompletableFuture<Void> doStart() {
        LOG.infof("Starting Java Debug Server (embedded mode via JDTLS)");
        setStatus(ServerStatus.RUNNING);
        setReady(true);
        return CompletableFuture.completedFuture(null);
    }


    /**
     * Override enrichLaunchConfiguration to add Java-specific resolution.
     * This is called by DapSession.launch() before connecting to the debug adapter.
     */
    @Override
    public CompletableFuture<Map<String, Object>> enrichLaunchConfiguration(
            Map<String, Object> launchConfig,
            String sessionId) {

        String request = (String) launchConfig.get("request");
        LOG.infof("Enriching configuration for Java, request type: %s", request);

        // Resolve classpath, java executable, etc. via JDTLS commands
        return resolveLaunchConfiguration(launchConfig, sessionId)
                .thenCompose(enrichedConfig -> {
                    // For "attach" request, connect directly to the target application
                    // For "launch" request, start debug session in JDTLS first
                    if ("attach".equals(request)) {
                        return handleAttachRequest(enrichedConfig, sessionId);
                    } else {
                        return handleLaunchRequest(enrichedConfig, sessionId);
                    }
                });
    }

    /**
     * Handle "launch" request: start debug session in JDTLS, then connect to it.
     */
    private CompletableFuture<Map<String, Object>> handleLaunchRequest(
            Map<String, Object> enrichedConfig,
            String sessionId) {

        // Start debug session (loads java-debug bundle in JDTLS and returns port)
        return startDebugSession(sessionId)
                .thenCompose(port -> {
                    String workspaceRootUri = getWorkspace().getNormalizedUri();

                    getTraceCollector().addTrace(
                            workspaceRootUri,
                            sessionId,
                            String.format("Connecting to DAP server on port %d...", port)
                    );

                    // Use DapServer's connectToSocket() - much simpler!
                    return connectToSocket("localhost", port)
                            .thenApply(v -> {
                                getTraceCollector().addTrace(
                                        workspaceRootUri,
                                        sessionId,
                                        String.format("Connected to DAP server on port %d", port)
                                );
                                return enrichedConfig;
                            });
                });
    }

    /**
     * Handle "attach" request: start debug session in JDTLS to load java-debug bundle,
     * then the DAP session will use the hostName/port from the config to attach.
     */
    private CompletableFuture<Map<String, Object>> handleAttachRequest(
            Map<String, Object> enrichedConfig,
            String sessionId) {

        String workspaceRootUri = getWorkspace().getNormalizedUri();

        // For attach, we still need to start the debug session in JDTLS
        // to load the java-debug bundle, but we won't use its port.
        // Instead, the DAP protocol will use hostName/port from enrichedConfig
        // to attach to the target application.
        return startDebugSession(sessionId)
                .thenCompose(jdtlsPort -> {
                    getTraceCollector().addTrace(
                            workspaceRootUri,
                            sessionId,
                            String.format("Connecting to DAP server on port %d (JDTLS debug adapter)...", jdtlsPort)
                    );

                    // Connect to JDTLS debug adapter
                    return connectToSocket("localhost", jdtlsPort)
                            .thenApply(v -> {
                                getTraceCollector().addTrace(
                                        workspaceRootUri,
                                        sessionId,
                                        String.format("Connected to DAP server on port %d. Will attach to target at %s:%s",
                                                jdtlsPort,
                                                enrichedConfig.get("hostName"),
                                                enrichedConfig.get("port"))
                                );
                                return enrichedConfig;
                            });
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
                    getTraceCollector().addTrace(
                            workspaceRootUri,
                            sessionId,
                            String.format("ERROR resolving launch config: %s", ex.getMessage())
                    );
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

        String workspaceRootUri = getWorkspace().getNormalizedUri();
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

            getTraceCollector().addTrace(
                    workspaceRootUri,
                    sessionId,
                    String.format("Attach config validated: hostName=%s, port=%d", hostName, port)
            );

            return CompletableFuture.completedFuture(launchConfig);
        }

        // Check if processId is configured (not supported in this implementation)
        if (processIdObj != null) {
            // Note: processId resolution would require additional JDTLS support
            // For now, we return an error
            String error = "Attach by processId is not yet supported. Please use hostName and port.";
            LOG.error(error);
            getTraceCollector().addTrace(
                    workspaceRootUri,
                    sessionId,
                    String.format("ERROR: %s", error)
            );
            return CompletableFuture.failedFuture(new UnsupportedOperationException(error));
        }

        // Neither hostName/port nor processId is configured
        String error = "Please specify the hostName/port directly, or provide the processId of the remote debuggee in the launch configuration.";
        LOG.error(error);
        getTraceCollector().addTrace(
                workspaceRootUri,
                sessionId,
                String.format("ERROR: %s", error)
        );
        return CompletableFuture.failedFuture(new IllegalArgumentException(error));
    }

    /**
     * Start the debug session by calling vscode.java.startDebugSession.
     * This loads the java-debug bundle in JDTLS and returns the port.
     *
     * @param sessionId The session ID for tracing
     * @return The port where the debug adapter is listening
     */
    public CompletableFuture<Integer> startDebugSession(String sessionId) {
        String workspaceRootUri = getWorkspace().getNormalizedUri();

        // Call vscode.java.startDebugSession via bindRequest mechanism
        return request(CMD_START_DEBUG_SESSION, List.of())
                .thenApply(result -> {
                    if (!(result instanceof Number)) {
                        String error = String.format("%s returned non-numeric result: %s", CMD_START_DEBUG_SESSION, result);
                        LOG.error(error);
                        throw new IllegalStateException(error);
                    }

                    int port = ((Number) result).intValue();
                    LOG.infof("Debug adapter listening on port: %d", port);

                    getTraceCollector().addTrace(
                            workspaceRootUri,
                            sessionId,
                            String.format("Debug adapter listening on port: %d", port)
                    );

                    return port;
                });
    }

    // ===== Private helper methods for JDTLS commands =====

    private CompletableFuture<Object> validateLaunchConfig(
            String workspaceRootUri,
            String mainClass,
            String projectName,
            String sessionId) {

        List<Object> args = new java.util.ArrayList<>();
        args.add(workspaceRootUri);
        args.add(mainClass);
        args.add(projectName);
        args.add(false);

        return request(CMD_VALIDATE_LAUNCH_CONFIG, args)
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
        // Build workspace expects a JSON string (not a structured object)
        String buildArg = String.format("{\"mainClass\":\"%s\",\"isFullBuild\":true}", mainClass);

        return request(CMD_BUILD_WORKSPACE, List.of(buildArg))
                .handle((result, ex) -> {
                    if (ex != null) {
                        String error = String.format("Error calling %s: %s", CMD_BUILD_WORKSPACE, ex.getMessage());
                        LOG.error(error, ex);
                        throw new RuntimeException(error, ex);
                    }
                    LOG.debugf("Build workspace result: %s", result);
                    return result;
                });
    }

    private CompletableFuture<List<List<String>>> resolveClasspath(
            String mainClass,
            String projectName,
            String sessionId) {

        // Use Arrays.asList instead of List.of because List.of doesn't allow null values
        List<Object> args = java.util.Arrays.asList(mainClass, projectName, null);

        return request(CMD_RESOLVE_CLASSPATH, args)
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

        return request(CMD_RESOLVE_JAVA_EXECUTABLE, args)
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
