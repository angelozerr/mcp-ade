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
package com.ibm.mcp.languagetools.bsp.server;

import ch.epfl.scala.bsp4j.BuildClient;
import ch.epfl.scala.bsp4j.BuildClientCapabilities;
import ch.epfl.scala.bsp4j.BuildServer;
import ch.epfl.scala.bsp4j.DidChangeBuildTarget;
import ch.epfl.scala.bsp4j.InitializeBuildParams;
import ch.epfl.scala.bsp4j.JvmBuildServer;
import ch.epfl.scala.bsp4j.LogMessageParams;
import ch.epfl.scala.bsp4j.PrintParams;
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams;
import ch.epfl.scala.bsp4j.ShowMessageParams;
import ch.epfl.scala.bsp4j.TaskFinishParams;
import ch.epfl.scala.bsp4j.TaskProgressParams;
import ch.epfl.scala.bsp4j.TaskStartParams;
import com.ibm.mcp.languagetools.configuration.ServerTrace;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerBase;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.server.ServerType;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Build Server Protocol (BSP) server wrapper.
 * Manages lifecycle of a BSP server (build server) for a workspace.
 *
 * <p>This is a BSP <b>client</b> that connects to external BSP servers
 * (like Gradle BSP, Bazel BSP, sbt BSP) via JSON-RPC over stdin/stdout.</p>
 *
 * <p>Similar to {@link com.ibm.mcp.languagetools.lsp.server.LspServer}
 * and {@link com.ibm.mcp.languagetools.dap.server.DapServer} but for build operations
 * instead of language features or debugging.</p>
 *
 * @see <a href="https://build-server-protocol.github.io/docs/specification">Build Server Protocol Specification</a>
 */
public class BspServer extends ServerBase<BspServerConfig> {

    private static final Logger LOG = Logger.getLogger(BspServer.class);

    private interface FullBuildServer extends BuildServer, JvmBuildServer {}

    private FullBuildServer buildServer;

    public BspServer(BspServerConfig config, Workspace workspace) {
        super(config, workspace);
    }

    @Override
    protected TraceCollector initializeTraceCollector(Workspace workspace) {
        return workspace.getApplication().getBspTraceCollector();
    }

    @Override
    public ServerTrace getServerTrace() {
        return getWorkspace().getWorkspaceConfiguration().getBspTraceLevel(getConfig().getServerId());
    }

    @Override
    public ServerType getServerType() {
        return ServerType.BSP;
    }

    /**
     * Start the BSP server process and set up JSON-RPC communication.
     *
     * <p>Lifecycle:</p>
     * <ol>
     *   <li>Ensure the server is installed</li>
     *   <li>Launch the external BSP server process</li>
     *   <li>Start stderr monitoring</li>
     *   <li>Create JSON-RPC launcher over stdin/stdout</li>
     *   <li>Store the BuildServer proxy</li>
     *   <li>Set status to RUNNING</li>
     * </ol>
     *
     * @param progressMonitor progress monitor for installation and startup
     * @return future that completes when the server process is connected
     */
    public final CompletableFuture<Void> start(ProgressMonitor progressMonitor) {
        if (!prepareStart()) {
            return CompletableFuture.completedFuture(null);
        }

        return withErrorLogging(
                getConfig().ensureInstalled(getWorkspace(), this::setStatus, progressMonitor)
                        .thenRun(() -> {
                            try {
                                Process process = startProcess();

                                Launcher<FullBuildServer> launcher = new Launcher.Builder<FullBuildServer>()
                                        .setLocalService(new BspClientImpl())
                                        .setRemoteInterface(FullBuildServer.class)
                                        .setInput(process.getInputStream())
                                        .setOutput(process.getOutputStream())
                                        .setExecutorService(getExecutorService())
                                        .wrapMessages(consumer -> message -> {
                                            try {
                                                getTracing().log(message, consumer);
                                            } catch (Exception e) {
                                                LOG.warnf(e, "Error tracing BSP message: %s", e.getMessage());
                                            }
                                            consumer.consume(message);
                                        })
                                        .create();

                                buildServer = launcher.getRemoteProxy();
                                startListening(launcher);

                                setStatus(ServerStatus.RUNNING);
                                setStarted(true);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
        );
    }

    @Override
    protected String getWorkingDirectory() {
        String configured = getConfig().getWorkingDirectory();
        if (configured != null) {
            return configured;
        }
        return getWorkspace().getRootPath().toString();
    }

    /**
     * Perform the BSP initialization handshake.
     * Sends {@code buildInitialize} and then {@code onBuildInitialized}.
     *
     * @return future that completes when the initialization handshake is done
     */
    public CompletableFuture<Void> initialize() {
        String rootUri = getWorkspace().getRootUri().toString();

        BuildClientCapabilities capabilities = new BuildClientCapabilities(
                List.of("java", "scala", "kotlin")
        );

        InitializeBuildParams initParams = new InitializeBuildParams(
                "MCP Language Tools",  // displayName
                "0.1.0",               // version
                "2.0.0",               // bspVersion
                rootUri,               // rootUri
                capabilities           // capabilities
        );

        return buildServer.buildInitialize(initParams)
                .thenAccept(result -> {
                    buildServer.onBuildInitialized();
                    setReady(true);
                    addTrace(String.format("BSP server %s initialized (capabilities: %s)",
                            getConfig().getName(), result.getCapabilities()));
                });
    }

    /**
     * Shut down the BSP server gracefully.
     *
     * <p>Sends {@code buildShutdown} followed by {@code onBuildExit},
     * then destroys the server process.</p>
     *
     * @return future that completes when shutdown is finished
     */
    public CompletableFuture<Void> shutdown() {
        if (getStatus() == ServerStatus.STOPPED) {
            return CompletableFuture.completedFuture(null);
        }

        setStatus(ServerStatus.STOPPING);

        CompletableFuture<Void> shutdownFuture;

        if (buildServer != null) {
            shutdownFuture = buildServer.buildShutdown()
                    .thenRun(() -> buildServer.onBuildExit())
                    .exceptionally(e -> {
                        LOG.warnf(e, "Error during BSP shutdown: %s", e.getMessage());
                        return null;
                    });
        } else {
            shutdownFuture = CompletableFuture.completedFuture(null);
        }

        return shutdownFuture.thenRun(() -> {
            buildServer = null;

            cancelListeningFuture();

            destroyProcess(5000, 3000);
            setStatus(ServerStatus.STOPPED);
            setStarted(false);
            setReady(false);
        });
    }

    /**
     * Returns the BSP server proxy for making build protocol requests.
     *
     * @return the remote BuildServer proxy, or null if not connected
     */
    public BuildServer getBuildServer() {
        return buildServer;
    }

    /**
     * Returns the JVM-specific BSP server proxy for JVM build target queries.
     *
     * @return the remote JvmBuildServer proxy, or null if not connected
     */
    public JvmBuildServer getJvmBuildServer() {
        return buildServer;
    }

    /**
     * BSP client implementation that receives notifications and events from the BSP server.
     */
    private class BspClientImpl implements BuildClient {

        @Override
        public void onBuildShowMessage(ShowMessageParams params) {
            LOG.infof("[%s] BSP show message: %s", getConfig().getServerId(), params.getMessage());
            addTrace(String.format("[showMessage] %s: %s", params.getType(), params.getMessage()));
        }

        @Override
        public void onBuildLogMessage(LogMessageParams params) {
            LOG.debugf("[%s] BSP log message: %s", getConfig().getServerId(), params.getMessage());
            addTrace(String.format("[logMessage] %s: %s", params.getType(), params.getMessage()));
        }

        @Override
        public void onBuildPublishDiagnostics(PublishDiagnosticsParams params) {
            LOG.debugf("[%s] BSP diagnostics for %s: %d diagnostics",
                    getConfig().getServerId(),
                    params.getTextDocument().getUri(),
                    params.getDiagnostics() != null ? params.getDiagnostics().size() : 0);
            addTrace(String.format("[publishDiagnostics] %s: %d diagnostics",
                    params.getTextDocument().getUri(),
                    params.getDiagnostics() != null ? params.getDiagnostics().size() : 0));
        }

        @Override
        public void onBuildTaskStart(TaskStartParams params) {
            String message = params.getMessage();
            if (message != null) {
                setStatusMessage(message);
            }
            LOG.debugf("[%s] BSP task started: %s", getConfig().getServerId(), message);
        }

        @Override
        public void onBuildTaskProgress(TaskProgressParams params) {
            String message = params.getMessage();
            if (message != null) {
                setStatusMessage(message);
            }
            LOG.debugf("[%s] BSP task progress: %s (%d/%d)",
                    getConfig().getServerId(),
                    message,
                    params.getProgress(),
                    params.getTotal());
        }

        @Override
        public void onBuildTaskFinish(TaskFinishParams params) {
            setStatusMessage(null);
            LOG.debugf("[%s] BSP task finished: %s (status: %s)",
                    getConfig().getServerId(),
                    params.getMessage(),
                    params.getStatus());
        }

        @Override
        public void onBuildTargetDidChange(DidChangeBuildTarget params) {
            LOG.infof("[%s] BSP build targets changed: %d changes",
                    getConfig().getServerId(),
                    params.getChanges() != null ? params.getChanges().size() : 0);
            addTrace(String.format("[buildTargetDidChange] %d changes",
                    params.getChanges() != null ? params.getChanges().size() : 0));
        }

        @Override
        public void onRunPrintStdout(PrintParams params) {
            LOG.debugf("[%s] BSP stdout: %s", getConfig().getServerId(), params.getMessage());
            addTrace(String.format("[stdout] %s", params.getMessage()));
        }

        @Override
        public void onRunPrintStderr(PrintParams params) {
            LOG.debugf("[%s] BSP stderr: %s", getConfig().getServerId(), params.getMessage());
            addTrace(String.format("[stderr] %s", params.getMessage()));
        }
    }
}
