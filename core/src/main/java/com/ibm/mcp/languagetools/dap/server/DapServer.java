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
package com.ibm.mcp.languagetools.dap.server;

import com.ibm.mcp.languagetools.dap.client.DapClient;
import com.ibm.mcp.languagetools.dap.session.DapSession;
import com.ibm.mcp.languagetools.dap.transport.SocketTransportStreams;
import com.ibm.mcp.languagetools.dap.transport.StdioTransportStreams;
import com.ibm.mcp.languagetools.dap.transport.TransportStreams;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressStep;
import com.ibm.mcp.languagetools.server.ServerBase;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.server.ServerType;
import com.ibm.mcp.languagetools.server.ServerVariables;
import com.ibm.mcp.languagetools.configuration.ServerTrace;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.eclipse.lsp4j.debug.launch.DSPLauncher;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Debug Adapter Protocol (DAP) server wrapper.
 * Manages lifecycle of a DAP server (debug adapter) for a workspace.
 * Similar to LspServer but for debugging instead of language features.
 *
 * @see <a href="https://microsoft.github.io/debug-adapter-protocol/specification">Debug Adapter Protocol Specification</a>
 */
public class DapServer extends ServerBase<DapServerConfig> {

    private static final Logger LOG = Logger.getLogger(DapServer.class);

    private IDebugProtocolServer debugServer;
    private DapClient dapClient;
    private Integer allocatedPort;
    private TransportStreams transportStreams;
    private final List<TransportStreams> childTransportStreams = new CopyOnWriteArrayList<>();
    private final DapSession session;

    public DapServer(DapSession session, DapServerConfig config, Workspace workspace) {
        super(config, workspace, config.getServerId() + "#" + session.getSessionId());
        this.session = session;
    }

    /**
     * Returns the debug session associated with this server.
     */
    public DapSession getSession() {
        return session;
    }

    @Override
    protected TraceCollector initializeTraceCollector(Workspace workspace) {
        return workspace.getApplication().getDapTraceCollector();
    }

    @Override
    public ServerTrace getServerTrace() {
        return getWorkspace().getWorkspaceConfiguration().getDapTraceLevel(getConfig().getServerId());
    }

    @Override
    public ServerType getServerType() {
        return ServerType.DAP;
    }

    /**
     * Start the debug adapter and create the launcher.
     * Does NOT send initialize request - that's done later in connectAndInitialize().
     * <p>
     * Two modes:
     * <ul>
     *   <li><b>Standalone</b>: Launches external process via {@code launch} command</li>
     *   <li><b>Embedded</b>: Calls LSP method via {@code launchMethod} to get DAP port</li>
     * </ul>
     */
    public final CompletableFuture<Void> start(ProgressMonitor progressMonitor) {
        return start(progressMonitor, null);
    }

    public final CompletableFuture<Void> start(ProgressMonitor progressMonitor, OperationEntry operationEntry) {
        if (!prepareStart()) {
            return CompletableFuture.completedFuture(null);
        }

        if (session.isAttach()) {
            return withErrorLogging(doStart());
        }

        return withErrorLogging(
            getConfig().ensureInstalled(
                    getWorkspace(),
                    this::setStatus,
                    progressMonitor,
                    false,
                    operationEntry)
                .thenCompose(v -> {
                    progressMonitor.beginStep(ProgressStep.STARTING);
                    return doStart();
                })
        );
    }

    /**
     * Subclass hook called after installation is ensured.
     * Standalone servers launch a process; embedded servers (e.g., java-debug) can just set RUNNING.
     * For attach mode with a server.json "attach" block, skip process launch -
     * the connection will be established in enrichLaunchConfiguration().
     */
    protected CompletableFuture<Void> doStart() {
        if (session.isAttach() && getConfig().getAttach() != null) {
            LOG.infof("Attach mode - skipping process launch");
            setStatus(ServerStatus.RUNNING);
            setReady(true);
            return CompletableFuture.completedFuture(null);
        }
        if (getConfig().getCommand() != null) {
            return startServerProcess()
                .thenCompose(this::waitForServerReady)
                .thenCompose(this::createLauncher);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Enrich launch configuration before launching.
     * Override this method in subclasses to add custom resolution logic.
     * For example, JavaDebugServer resolves classpath, java executable, etc.
     *
     * <p>Default implementation handles attach mode for standalone DAP servers (e.g., debugpy):
     * resolves {@code $connect.host} and {@code $connect.port} JSON path references
     * from the server.json {@code attach} block against the launch configuration,
     * then connects to the DAP server via socket.</p>
     *
     * @param launchConfig The initial launch configuration
     * @param sessionId The session ID for tracing
     * @param progressMonitor Progress monitor for reporting status
     * @return CompletableFuture with enriched configuration
     */
    public CompletableFuture<Map<String, Object>> enrichLaunchConfiguration(
            Map<String, Object> launchConfig,
            String sessionId,
            ProgressMonitor progressMonitor) {
        // For attach mode with a server.json "attach" block, resolve address/port and connect
        if (session.isAttach()) {
            Map<String, Object> attachConfig = getConfig().getAttach();
            if (attachConfig != null) {
                String addressRef = attachConfig.get("address") instanceof String s ? s : null;
                Object portRef = attachConfig.get("port");

                String host = resolveJsonPath(addressRef, launchConfig);
                int port = resolveJsonPathAsInt(portRef, launchConfig);

                if (host != null && port > 0) {
                    LOG.infof("Attach mode: connecting to DAP server at %s:%d", host, port);
                    return connectToSocket(host, port)
                            .thenApply(v -> launchConfig);
                }
            }
        }
        return CompletableFuture.completedFuture(launchConfig);
    }

    /**
     * Resolve a JSON path reference (e.g., {@code $connect.host}) against a configuration map.
     * If the value doesn't start with {@code $}, it is returned as-is.
     */
    private static String resolveJsonPath(String ref, Map<String, Object> config) {
        if (ref == null) {
            return null;
        }
        if (!ref.startsWith("$")) {
            return ref;
        }
        String path = ref.substring(1);
        Object result = walkPath(path, config);
        return result != null ? result.toString() : null;
    }

    /**
     * Resolve a JSON path reference to an integer.
     * Handles both {@code $} references and literal numeric values.
     */
    private static int resolveJsonPathAsInt(Object ref, Map<String, Object> config) {
        if (ref == null) {
            return -1;
        }
        if (ref instanceof Number n) {
            return n.intValue();
        }
        if (ref instanceof String s) {
            if (s.startsWith("$")) {
                Object result = walkPath(s.substring(1), config);
                if (result instanceof Number n) {
                    return n.intValue();
                }
                if (result instanceof String str) {
                    try {
                        return Integer.parseInt(str);
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            } else {
                try {
                    return Integer.parseInt(s);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static Object walkPath(String path, Map<String, Object> map) {
        String[] parts = path.split("\\.");
        Object current = map;
        for (String part : parts) {
            if (current instanceof Map<?, ?> m) {
                current = m.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Start the DAP server process.
     */
    private CompletableFuture<DAPServerReadyTracker> startServerProcess() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var config = super.getConfig();
                setStatus(ServerStatus.STARTING);
                LOG.infof("Starting DAP server: %s", config.getName());

                final Process serverProcess = startProcess();

                // Create server ready tracker
                DAPServerReadyTracker readyTracker = getServerReadyTracker(config);

                return readyTracker;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, getExecutorService());
    }

    private DAPServerReadyTracker getServerReadyTracker(DapServerConfig config) {
        ServerReadyConfig readyConfig = config.getServerReadyConfig();
        if (allocatedPort != null && readyConfig.getPort() == null) {
            readyConfig = new ServerReadyConfig("127.0.0.1", allocatedPort);
        }

        return new DAPServerReadyTracker(
            readyConfig,
            getServerProcess(),
                this::addTrace
        );
    }

    /**
     * Wait for the DAP server to be ready.
     */
    private CompletableFuture<DAPServerReadyTracker> waitForServerReady(DAPServerReadyTracker readyTracker) {
        return readyTracker.track(getExecutorService())
            .thenApply(v -> {
                LOG.infof("DAP server ready, creating launcher...");

                addTrace(String.format("DAP server ready (address=%s, port=%s)",
                        readyTracker.getAddress(), readyTracker.getPort()));

                return readyTracker;
            });
    }

    /**
     * Create the LSP4J launcher (but DON'T send initialize yet).
     * Like lsp4ij: just setup the transport and launcher, initialization happens in connectAndInitialize().
     */
    private CompletableFuture<Void> createLauncher(DAPServerReadyTracker tracker) {
        return CompletableFuture.supplyAsync(() -> {
            try {

                // Create transport streams
                // Like lsp4ij: if a port was detected/allocated, use SOCKET, else use STDIO
                String address = tracker.getAddress();
                Integer port = tracker.getPort();

                if (port != null) {
                    // Socket transport - server is listening on a port
                    String host = address != null ? address : "127.0.0.1";
                    LOG.infof("Creating socket transport to %s:%d", host, port);
                    transportStreams = new SocketTransportStreams(host, port);

                    // Stdout is free — forward program output to the session
                    tracker.setStdoutConsumer(session::onProcessOutput);
                } else {
                    // Stdio transport - use process stdin/stdout
                    LOG.infof("Creating stdio transport (using process stdin/stdout)");
                    InputStream in = getServerProcess().getInputStream();
                    OutputStream out = getServerProcess().getOutputStream();
                    transportStreams = new StdioTransportStreams(in, out);
                }

                // Create launcher from transport streams
                createLauncherFromTransport(transportStreams);

                LOG.infof("Launcher created and listening, ready for initialization");
                setStatus(ServerStatus.RUNNING);
                setReady(true);

                return null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, getExecutorService());
    }

    /**
     * Factory method to create a DapClient instance.
     * Subclasses can override to provide specialized implementations (e.g., JavaDebugClient).
     *
     * @return a new DapClient instance
     */
    protected DapClient createDapClient() {
        return new DapClient();
    }

    /**
     * Factory method to create a child DapClient instance with a parent.
     * Subclasses should override this if they override createDapClient().
     *
     * @param parentClient the parent DapClient
     * @return a new child DapClient instance
     */
    public DapClient createDapClient(DapClient parentClient) {
        return new DapClient(parentClient);
    }

    /**
     * Create launcher from existing transport streams.
     * Used by both startServerProcess() and connectToSocket().
     */
    protected void createLauncherFromTransport(TransportStreams transport) {
        dapClient = createDapClient();
        dapClient.setExecutorService(getExecutorService());

        // Wrapper for tracing
        Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
                dapClient,
                transport.getInputStream(),
                transport.getOutputStream(),
                getExecutorService(),
                consumer -> message -> {
                    try {
                        getTracing().log(message, consumer);
                    } catch (Exception e) {
                        LOG.warnf(e, "Error tracing DAP message: %s", e.getMessage());
                    }
                    consumer.consume(message);
                });

        debugServer = launcher.getRemoteProxy();
        startListening(launcher);
    }

    /**
     * Connect to an existing DAP server via socket (for embedded mode).
     * Used when the DAP server is already running and listening on a port.
     *
     * @param host The host (e.g., "localhost")
     * @param port The port where DAP server is listening
     * @return CompletableFuture that completes when connected
     */
    protected CompletableFuture<Void> connectToSocket(String host, int port) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.infof("Connecting to DAP server at %s:%d", host, port);

                // Create socket transport
                transportStreams = new SocketTransportStreams(host, port);

                // Create launcher
                createLauncherFromTransport(transportStreams);

                LOG.infof("Connected to DAP server at %s:%d", host, port);
                setStatus(ServerStatus.RUNNING);
                setReady(true);

                return null;
            } catch (Exception e) {
                throw new RuntimeException("Failed to connect to DAP server at " + host + ":" + port, e);
            }
        }, getExecutorService());
    }

    /**
     * Create a child launcher for a child debug session.
     * Like lsp4ij: each child session creates a NEW socket connection to the DAP server.
     * For socket transports, this creates a new Socket to the same port.
     * For stdio transports, child sessions are not supported.
     *
     * @param childClient the child DapClient that will receive events
     * @return the remote proxy for sending DAP requests
     */
    public IDebugProtocolServer createChildLauncher(DapClient childClient) {
        if (transportStreams == null) {
            throw new IllegalStateException("Transport streams not initialized");
        }

        LOG.infof("Creating child launcher with new transport connection");

        if (!(transportStreams instanceof SocketTransportStreams parentSocket)) {
            throw new UnsupportedOperationException(
                "Child sessions only supported for socket transport, not stdio");
        }

        String host = parentSocket.getSocket().getInetAddress().getHostAddress();
        int port = parentSocket.getSocket().getPort();

        LOG.infof("Creating new socket connection to %s:%d for child", host, port);
        TransportStreams childTransport;
        try {
            childTransport = new SocketTransportStreams(host, port);
        } catch (IOException e) {
            throw new RuntimeException("Failed to connect to child debug adapter at " + host + ":" + port, e);
        }
        try {
            Launcher<IDebugProtocolServer> launcher = DSPLauncher.createClientLauncher(
                    childClient,
                    childTransport.getInputStream(),
                    childTransport.getOutputStream(),
                    getExecutorService(),
                    consumer -> message -> {
                        if (getServerTrace() != ServerTrace.off) {
                            try {
                                getTracing().log(message, consumer);
                            } catch (Exception e) {
                                LOG.warnf(e, "Error tracing DAP message: %s", e.getMessage());
                            }
                        }
                        consumer.consume(message);
                    });

            launcher.startListening();
            childTransportStreams.add(childTransport);

            LOG.infof("Child launcher created and listening on new connection");
            return launcher.getRemoteProxy();
        } catch (Exception e) {
            try {
                childTransport.close();
            } catch (Exception closeEx) {
                e.addSuppressed(closeEx);
            }
            throw new RuntimeException("Failed to create child launcher", e);
        }
    }

    /**
     * Stop the debug adapter.
     * Closes transport, kills the process, and updates status.
     * Note: disconnect() should be called by the caller (DapSession) before this method.
     */
    public void stop() {
        var config = super.getConfig();
        try {
            LOG.infof("Stopping DAP server: %s", config.getName());

            // Kill the server process FIRST to unblock the JSON-RPC message reader
            destroyProcess(5000, 2000);

            // Close child transport streams first
            for (TransportStreams childTransport : childTransportStreams) {
                try {
                    childTransport.close();
                } catch (Exception e) {
                    LOG.debugf("Error closing child transport (expected if already closed): %s", e.getMessage());
                }
            }
            childTransportStreams.clear();

            // Then close parent transport streams
            if (transportStreams != null) {
                try {
                    transportStreams.close();
                } catch (Exception e) {
                    LOG.debugf("Error closing transport streams (expected if already closed): %s", e.getMessage());
                }
            }

            setStatus(ServerStatus.STOPPED);
            setReady(false);
            LOG.infof("DAP server stopped: %s", config.getName());

        } catch (Exception e) {
            LOG.errorf(e, "Error stopping DAP server: %s", config.getServerId());
            setStatus(ServerStatus.STOPPED);
            setReady(false);
        }
    }

    /**
     * Build the command to launch the debug adapter.
     */
    @Override
    protected List<String> buildCommand() throws IOException {
        var config = super.getConfig();
        String cmd = config.getCommand();
        if (cmd == null) {
            throw new IOException("No launch command configured for current OS");
        }
        cmd = ServerVariables.resolve(cmd, config);

        if (cmd.contains("${port}")) {
            int port = getAvailablePort();
            cmd = cmd.replace("${port}", String.valueOf(port));
            allocatedPort = port;
        }

        return parseCommandLine(cmd);
    }

    /**
     * Find an available TCP port for the DAP server.
     */
    private static int getAvailablePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            LOG.warnf("Failed to find available port, using default 4711: %s", e.getMessage());
            return 4711; // Fallback port
        }
    }


    // Getters

    /**
     * Returns the DAP server proxy for sending debug protocol requests.
     */
    public IDebugProtocolServer getDebugServer() {
        return debugServer;
    }

    /**
     * Returns the DAP client that receives events and reverse requests.
     */
    public DapClient getDapClient() {
        return dapClient;
    }

}
