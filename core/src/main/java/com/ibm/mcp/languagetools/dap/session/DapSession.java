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
package com.ibm.mcp.languagetools.dap.session;

import com.ibm.mcp.languagetools.dap.client.DapClient;
import com.ibm.mcp.languagetools.dap.client.DapEventListener;
import com.ibm.mcp.languagetools.dap.server.DapServer;
import com.ibm.mcp.languagetools.dap.server.DapServerConfig;
import com.ibm.mcp.languagetools.dap.server.DapServerFactoryRegistry;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressStep;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.Thread;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents a single debug session.
 * One session = one program being debugged via DAP.
 * <p>
 * Lifecycle:
 * 1. create() - Create session with language/name
 * 2. setBreakpoint() - Set breakpoints before launch
 * 3. launch() or attach() - Start debugging
 * 4. step/continue/evaluate operations
 * 5. terminate() - End session
 */
public class DapSession implements DapEventListener {

    private static final Logger LOG = Logger.getLogger(DapSession.class);

    /**
     * Enum for tracking who created/launched a debug session.
     */
    public enum SessionActor {
        AI_AGENT("AI Agent"),    // Created/launched by an AI agent (Claude, etc.)
        MANUAL("Manual"),        // Created/launched manually via UI/API
        UNKNOWN("Unknown");      // Unknown source

        private final String displayName;

        SessionActor(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private final String sessionId;
    /**
     * Composite context ID "serverId#sessionId" for trace messages.
     */
    private final String contextId;
    private final String language;
    private final String sessionName;
    private final SessionActor createdBy; // Who created the session
    private final DapServerConfig serverConfig;
    private final DapServer dapServer; // Not final - can be recreated after error
    private final Workspace workspace;
    private final TraceCollector traceCollector;
    private final DapProgramOutput programOutput; // Captures stdout/stderr from debugged program
    private final Instant createdAt; // When the session was created

    private SessionState state = SessionState.CREATED;
    private volatile String errorMessage;
    private boolean attachMode = false;
    private boolean debugMode = false; // Default to run mode (without debugging)
    private SessionActor launchedBy; // Who last launched the session
    private Instant launchedAt; // When the session was last launched
    private Runnable stateChangeCallback; // Callback when session state changes
    private final Map<String, BreakpointInfo> breakpoints = new ConcurrentHashMap<>();
    private final Map<String, InstructionBreakpointInfo> instructionBreakpoints = new ConcurrentHashMap<>();
    private Thread[] threads = new Thread[0];
    private StackFrame[] currentStackFrames = new StackFrame[0];
    private Integer currentThreadId;
    private Map<String, Object> launchConfiguration; // Store the launch configuration used
    private boolean sentTerminateRequest = false; // Track if terminate request was sent

    // Parent-child session support
    private final List<DapSession> childSessions = new ArrayList<>();
    private boolean isChildSession = false; // True if this is a child session created via startDebugging

    // Track which server has the active thread (for routing stackTrace/scopes/variables requests)
    // In VS Code JS Debug, child sessions handle the actual debugging, so we need to route to the right server
    private IDebugProtocolServer activeServer; // The server that has the currentThreadId

    // Track pending CompletableFutures to cancel them if session is terminated
    private final Set<CompletableFuture<?>> pendingRequests = ConcurrentHashMap.newKeySet();

    public enum SessionState {
        CREATED,
        LAUNCHING,
        ATTACHING,
        RUNNING,
        PAUSED,
        TERMINATED,
        /** Launch request failed (e.g., program does not exist, invalid launch configuration). */
        LAUNCH_FAILED,
        /** Attach request failed (e.g., target process not found, permission denied). */
        ATTACH_FAILED,
        /** Generic error (e.g., DAP server process crashed). */
        ERROR
    }

    public static class BreakpointInfo {
        public final String breakpointId;
        public final String file;
        public final int line;
        public final String condition;
        public boolean verified;
        public Breakpoint dapBreakpoint;

        public BreakpointInfo(String breakpointId, String file, int line, String condition) {
            this.breakpointId = breakpointId;
            this.file = file;
            this.line = line;
            this.condition = condition;
            this.verified = false;
        }
    }

    public static class InstructionBreakpointInfo {
        public final String breakpointId;
        public final String instructionReference;
        public final Integer offset;
        public final String condition;
        public boolean verified;
        public Breakpoint dapBreakpoint;

        public InstructionBreakpointInfo(String breakpointId, String instructionReference, Integer offset, String condition) {
            this.breakpointId = breakpointId;
            this.instructionReference = instructionReference;
            this.offset = offset;
            this.condition = condition;
            this.verified = false;
        }
    }

    public DapSession(String sessionId,
                      String language,
                      String sessionName,
                      SessionActor createdBy,
                      DapServerConfig serverConfig,
                      Workspace workspace) {
        this.sessionId = sessionId;
        this.contextId = serverConfig.getServerId() + "#" + sessionId;
        this.language = language;
        this.sessionName = sessionName;
        this.createdBy = createdBy != null ? createdBy : SessionActor.UNKNOWN;
        this.createdAt = Instant.now(); // Record creation timestamp
        this.launchedBy = createdBy; // Initially, createdBy is also launchedBy
        this.serverConfig = serverConfig;
        this.workspace = workspace;
        this.traceCollector = workspace.getApplication().getDapTraceCollector();
        this.programOutput = new DapProgramOutput(); // Initialize program output buffer


        // Create DAP server using factory (allows custom implementations like JavaDebugServer)
        this.dapServer = DapServerFactoryRegistry.getInstance().createServer(this, serverConfig, workspace);

        // Note: setEventListener() must be called AFTER start() when dapClient is created
        // Listen to server status changes to update session state
        this.dapServer.addStatusChangeListener(this::onServerStatusChanged);
    }

    /**
     * Returns the composite context ID "serverId#sessionId" for trace messages.
     */
    private String getContextId() {
        return contextId;
    }

    /**
     * Create a child debug session in response to a startDebugging request.
     * This is used when the debug adapter wants to debug a spawned process.
     * The child inherits the debugMode from the parent session.
     *
     * @param configuration the DAP configuration for the child session
     */
    private CompletableFuture<DapClient> createChildSession(
            Map<String, Object> configuration) {

        // Inherit debugMode from parent session
        boolean debugMode = this.debugMode;

        LOG.infof("Creating child debug session for parent session: %s (inherited debugMode=%s)", sessionId, debugMode);

        // Generate a child session ID
        // IMPORTANT: Like lsp4ij, child sessions REUSE the same DAP server process
        // Only ONE vscode-js-debug server handles parent + all children
        // Each child gets its own DapClient but shares the same IDebugProtocolServer

        String childSessionId = sessionId + "-child-" + (childSessions.size() + 1);
        LOG.infof("Creating child debug session: %s", childSessionId);

        // Create a new DapClient for the child (like lsp4ij)
        // Each child gets its own DapClient + Launcher, sharing the same transport streams
        DapClient childClient = this.dapServer.createDapClient(this.dapServer.getDapClient());

        // Create a NEW launcher for this child (like lsp4ij)
        // The launcher will share the same transport streams but route messages correctly
        IDebugProtocolServer childDebugServer = this.dapServer.createChildLauncher(childClient);

        // IMPORTANT: Like lsp4ij DAPDebugProcess, child clients forward events to the parent session
        // But we need to know WHICH server to use for stackTrace/scopes/variables
        // So we wrap the events to capture the child's debug server
        final IDebugProtocolServer childServer = childDebugServer;
        childClient.setEventListener(new DapEventListener() {
            @Override
            public void onInitialized() {
                DapSession.this.onInitialized();
            }

            @Override
            public void onStopped(StoppedEventArguments event) {
                // Like lsp4ij: when stopped arrives, store THIS client's server as the active one
                LOG.infof("Child session %s stopped - setting as active server", childSessionId);
                activeServer = childServer;
                DapSession.this.onStopped(event);
            }

            @Override
            public void onContinued(ContinuedEventArguments event) {
                DapSession.this.onContinued(event);
            }

            @Override
            public void onExited(ExitedEventArguments event) {
                DapSession.this.onExited(event);
            }

            @Override
            public void onTerminated(TerminatedEventArguments event) {
                DapSession.this.onTerminated(event);
            }

            @Override
            public void onThread(ThreadEventArguments event) {
                DapSession.this.onThread(event);
            }

            @Override
            public void onOutput(OutputEventArguments event) {
                DapSession.this.onOutput(event);
            }

            @Override
            public void onBreakpoint(BreakpointEventArguments event) {
                DapSession.this.onBreakpoint(event);
            }

            @Override
            public void onModule(ModuleEventArguments event) {
                DapSession.this.onModule(event);
            }

            @Override
            public void onLoadedSource(LoadedSourceEventArguments event) {
                DapSession.this.onLoadedSource(event);
            }

            @Override
            public void onProcess(ProcessEventArguments event) {
                DapSession.this.onProcess(event);
            }

            @Override
            public void onCapabilities(CapabilitiesEventArguments event) {
                DapSession.this.onCapabilities(event);
            }

            @Override
            public void onProgressStart(ProgressStartEventArguments event) {
                DapSession.this.onProgressStart(event);
            }

            @Override
            public void onProgressUpdate(ProgressUpdateEventArguments event) {
                DapSession.this.onProgressUpdate(event);
            }

            @Override
            public void onProgressEnd(ProgressEndEventArguments event) {
                DapSession.this.onProgressEnd(event);
            }

            @Override
            public void onInvalidated(InvalidatedEventArguments event) {
                DapSession.this.onInvalidated(event);
            }

            @Override
            public void onMemory(MemoryEventArguments event) {
                DapSession.this.onMemory(event);
            }
        });

        // Initialize the child client with its own debug server proxy
        // IMPORTANT: Like lsp4ij, child sessions receive parent's breakpoints
        // by calling sendAllBreakpointsToServer with the child server
        return childClient.connectAndInitialize(
                        childDebugServer,  // Use the child's own debug server proxy
                        configuration,
                        debugMode,
                        this.serverConfig.getServerId(),
                        debugMode ? () -> sendAllBreakpointsToServer(childDebugServer) : null  // Send parent's breakpoints to child server
                )
                .thenApply(v -> childClient)
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to create child debug session");
                    throw new RuntimeException("Failed to create child debug session", ex);
                });
    }

    /**
     * Called when the DAP server status changes.
     * Propagates ERROR status to the session.
     * Note: DapSessionManager also listens to status changes and fires WebSocket events.
     */
    private void onServerStatusChanged(ServerStatus oldStatus,
                                       ServerStatus newStatus) {
        LOG.infof("DAP server status changed for session %s: %s -> %s", sessionId, oldStatus, newStatus);

        // Propagate ERROR status to session state
        if (newStatus == ServerStatus.ERROR) {
            setState(SessionState.ERROR);
        }
    }

    // ========== Lifecycle ==========

    /**
     * Initialize the DAP server and establish connection.
     * Installation happens automatically inside dapServer.start().
     */
    public CompletableFuture<Void> initialize(ProgressMonitor progressMonitor) {
        LOG.infof("Initializing DAP session: %s (%s)", sessionName, sessionId);
        return trackFuture(dapServer.start(progressMonitor)
                .thenAccept(v -> {
                    // Server is now RUNNING, session stays CREATED until launch
                    LOG.infof("DAP session initialized: %s", sessionId);
                    // Event listener will be set in launch() before connectAndInitialize
                })
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to initialize DAP session: %s", sessionId);
                    setState(SessionState.ERROR);

                    if (traceCollector.isEnabled()) {
                        traceCollector.addTrace(
                                workspace.getNormalizedUri(),
                                getContextId(),
                                "❌ Failed to initialize: " + ex.getMessage()
                        );
                    }

                    // Propagate exception instead of returning null
                    if (ex instanceof RuntimeException) {
                        throw (RuntimeException) ex;
                    }
                    throw new RuntimeException("Failed to initialize DAP session", ex);
                }));
    }

    /**
     * Track a CompletableFuture so it can be cancelled if the session terminates.
     * Automatically removes the future from tracking when it completes.
     *
     * @param future the future to track
     * @return the same future for chaining
     */
    private <T> CompletableFuture<T> trackFuture(CompletableFuture<T> future) {
        return trackFuture(future, null);
    }

    /**
     * Track a CompletableFuture so it can be cancelled if the session terminates.
     * Automatically removes the future from tracking when it completes.
     *
     * @param future the future to track
     * @return the same future for chaining
     */
    private <T> CompletableFuture<T> trackFuture(CompletableFuture<T> future, ProgressMonitor progressMonitor) {
        CompletableFuture<T> trackedFuture = future;

        pendingRequests.add(trackedFuture);
        trackedFuture.whenComplete((result, error) -> pendingRequests.remove(trackedFuture));

        return trackedFuture;
    }

    /**
     * Cancel all pending CompletableFutures.
     * Called when the session is terminated to unblock any waiting .join() calls.
     */
    private void cancelAllPendingRequests() {
        if (pendingRequests.isEmpty()) {
            return;
        }

        LOG.infof("Cancelling %d pending requests for session %s", pendingRequests.size(), sessionId);

        // Complete all pending futures exceptionally
        RuntimeException sessionTerminated = new RuntimeException(
                "Session " + sessionId + " terminated - cancelling pending request"
        );

        pendingRequests.forEach(future -> {
            if (!future.isDone()) {
                future.completeExceptionally(sessionTerminated);
            }
        });

        pendingRequests.clear();
    }

    /**
     * Launch with full launch configuration (from launch.json).
     *
     * @param launchConfig    the launch configuration
     * @param debugMode       true to launch in debug mode (with breakpoints), false to run without debugging
     * @param progressMonitor the MCP progress + cancellation
     */
    public CompletableFuture<Map<String, Object>> launch(Map<String, Object> launchConfig,
                                                         boolean debugMode,
                                                         SessionActor launchedBy,
                                                         ProgressMonitor progressMonitor) {
        return launch(launchConfig, debugMode, launchedBy, progressMonitor, null);
    }

    public CompletableFuture<Map<String, Object>> launch(Map<String, Object> launchConfig,
                                                         boolean debugMode,
                                                         SessionActor launchedBy,
                                                         ProgressMonitor progressMonitor,
                                                         OperationEntry serverEntry) {
        LOG.infof("Launching debug session: %s (debugMode=%s, launchedBy=%s) with config: %s", sessionId, debugMode, launchedBy, launchConfig);

        // Store launch configuration for later retrieval
        this.launchConfiguration = new HashMap<>(launchConfig);

        // Store who launched this session
        if (launchedBy != null) {
            this.launchedBy = launchedBy;
        }

        // Store debugMode for child sessions to inherit
        this.debugMode = debugMode;

        // Record launch timestamp
        this.launchedAt = Instant.now();

        // Reset tracking for this new launch
        this.sentTerminateRequest = false;
        SessionState previousState = this.state;
        this.attachMode = "attach".equals(launchConfig.get("request"));
        setState(isAttach() ? SessionState.ATTACHING : SessionState.LAUNCHING);

        // Report progress: Starting debug adapter
        if (progressMonitor != null) {
            progressMonitor.beginStep(ProgressStep.STARTING);
            progressMonitor.reportProgress(10.0, "Starting debug adapter");
        }

        OperationEntry installEntry = serverEntry != null ? serverEntry.addChild("installing") : null;

        // Restart server if not running (first launch or after crash)
        CompletableFuture<Void> initFuture;
        var serverStatus = dapServer.getStatus();
        LOG.infof("Launch requested: previousState=%s, serverStatus=%s", previousState, serverStatus);

        // If session was TERMINATED, stop server first to clear vscode-js-debug DI state
        if (previousState == SessionState.TERMINATED && serverStatus == ServerStatus.RUNNING) {
            LOG.infof("Session TERMINATED but server RUNNING - stopping server to clear state");
            initFuture = CompletableFuture.runAsync(() -> dapServer.stop())
                    .thenCompose(v -> initialize(progressMonitor));
        } else if (previousState == SessionState.CREATED
                || serverStatus == ServerStatus.NOT_STARTED
                || serverStatus == ServerStatus.START_FAILED
                || serverStatus == ServerStatus.ERROR
                || serverStatus == ServerStatus.STOPPED) {
            LOG.infof("Server not running or in error, starting and initializing...");
            initFuture = initialize(progressMonitor);
        } else {
            LOG.infof("Server already running, skipping init");
            initFuture = CompletableFuture.completedFuture(null);
        }

        return trackFuture(initFuture
                .whenComplete((v, initEx) -> {
                    if (installEntry != null) {
                        if (initEx != null) {
                            installEntry.fail(initEx.getMessage());
                        } else {
                            installEntry.complete();
                        }
                    }
                })
                .thenCompose(v -> {
            OperationEntry launchEntry = serverEntry != null
                    ? serverEntry.addChild(attachMode ? "attaching" : "launching") : null;
            // Verify server is actually running
            if (dapServer.getStatus() != ServerStatus.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "DAP server not running (status: " + dapServer.getStatus() + ")"));
            }

            // Resolve variables in launch config (like lsp4ij)
            Map<String, Object> resolvedConfig = resolveVariables(launchConfig);

            // Add workspace folder as cwd if not present
            if (!resolvedConfig.containsKey("cwd")) {
                String workspaceFolder = getWorkspaceFolderPath();
                resolvedConfig.put("cwd", workspaceFolder);
            }

            // For launch requests, add noDebug if running without debugging (like lsp4ij)
            String requestType = (String) resolvedConfig.get("request");
            if ("launch".equals(requestType) && !debugMode) {
                resolvedConfig.put("noDebug", true);
            }

            LOG.infof("Resolved launch config (debugMode=%s): %s", debugMode, resolvedConfig);

            // Report progress: Preparing launch configuration
            if (progressMonitor != null) {
                progressMonitor.beginStep(ProgressStep.EXECUTING);
                progressMonitor.reportProgress(30.0, "Preparing launch configuration");
            }

            // Enrich launch configuration (subclasses like JavaDebugServer can override)
            return dapServer.enrichLaunchConfiguration(resolvedConfig, sessionId)
                    .thenCompose(enrichedConfig -> {
                        LOG.infof("Connecting and initializing with enriched config");

                        // IMPORTANT: Set event listener BEFORE connectAndInitialize
                        // because runInTerminal is called during launch (inside connectAndInitialize)
                        if (dapServer.getDapClient() != null) {
                            dapServer.getDapClient().setEventListener(DapSession.this);
                            dapServer.getDapClient().setChildSessionFactory(this::createChildSession);
                        }

                        return dapServer.getDapClient().connectAndInitialize(
                                        dapServer.getDebugServer(),
                                        enrichedConfig,
                                        debugMode,  // Pass debugMode to control breakpoint initialization
                                        dapServer.getConfig().getServerId(),
                                        debugMode ? this::sendAllBreakpoints : null  // Send breakpoints if debug mode
                                )
                                .thenApply(result -> {
                                    if (launchEntry != null) {
                                        launchEntry.complete();
                                    }
                                    if (serverEntry != null) {
                                        serverEntry.complete();
                                    }
                                    if (state != SessionState.TERMINATED) {
                                        setState(SessionState.RUNNING);
                                    }
                                    LOG.infof("Debug session fully initialized (state=%s)", state);
                                    Map<String, Object> response = new HashMap<>();
                                    response.put("success", state != SessionState.TERMINATED);
                                    response.put("state", state.name().toLowerCase());
                                    response.put("message", state == SessionState.TERMINATED
                                            ? "Program terminated during launch"
                                            : "Debugging started");
                                    return response;
                                });
                    })
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            if (launchEntry != null) {
                                launchEntry.fail(ex.getMessage());
                            }
                            if (serverEntry != null) {
                                serverEntry.fail(ex.getMessage());
                            }
                            if (state == SessionState.LAUNCHING || state == SessionState.ATTACHING) {
                                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                                SessionState failedState = state == SessionState.LAUNCHING
                                        ? SessionState.LAUNCH_FAILED : SessionState.ATTACH_FAILED;
                                setState(failedState, cause.getMessage());
                            }
                            String error = String.format("DAP session launch failed: %s", ex.getMessage());
                            LOG.error(error, ex);

                            // Build full error message with stack trace
                            StringBuilder errorTrace = new StringBuilder();
                            errorTrace.append("ERROR launching DAP session:\n");
                            errorTrace.append(ex.getClass().getName()).append(": ").append(ex.getMessage()).append("\n");

                            // Add stack trace
                            Throwable cause = ex;
                            while (cause != null) {
                                for (StackTraceElement element : cause.getStackTrace()) {
                                    errorTrace.append("  at ").append(element).append("\n");
                                }
                                cause = cause.getCause();
                                if (cause != null) {
                                    errorTrace.append("Caused by: ").append(cause.getClass().getName())
                                            .append(": ").append(cause.getMessage()).append("\n");
                                }
                            }

                            if (dapServer.getTraceCollector().isEnabled()) {
                                dapServer.getTraceCollector().addTrace(
                                        workspace.getNormalizedUri(),
                                        getContextId(),
                                        errorTrace.toString()
                                );
                            }
                        }
                    });
        }), progressMonitor);
    }

    /**
     * Attach to a running process.
     *
     * @param processId       Process ID to attach to
     * @param progressMonitor MCP progress + cancellation
     */
    public CompletableFuture<Map<String, Object>> attach(int processId,
                                                         ProgressMonitor progressMonitor) {
        LOG.infof("Attaching debug session: %s to process: %d", sessionId, processId);
        setState(SessionState.ATTACHING);

        Map<String, Object> attachArgs = new HashMap<>();
        attachArgs.put("processId", processId);

        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("DAP server not initialized"));
        }

        return trackFuture(server.attach(attachArgs)
                .thenApply(v -> {
                    setState(SessionState.RUNNING);
                    return Map.of(
                            "success", true,
                            "state", "attached",
                            "message", "Attached to process " + processId
                    );
                }));
    }

    /**
     * Terminate the debug session.
     * Follows lsp4ij pattern: disconnect first, then stop server, handling errors gracefully.
     */
    public CompletableFuture<Void> terminate() {
        LOG.infof("Terminating DAP session: %s (with %d child sessions)", sessionId, childSessions.size());

        // Cancel all pending requests FIRST to unblock any waiting .join() calls
        cancelAllPendingRequests();

        // First, terminate all child sessions
        List<CompletableFuture<Void>> childTerminations = new ArrayList<>();
        for (DapSession child : childSessions) {
            childTerminations.add(child.terminate());
        }

        // DO NOT track terminate() itself - it should never be cancelled
        return CompletableFuture.allOf(childTerminations.toArray(new CompletableFuture[0]))
                .thenCompose(v -> {
                    // Then terminate this session
                    IDebugProtocolServer server = dapServer.getDebugServer();
                    if (server != null) {
                        // Like lsp4ij: prefer terminate() over disconnect() for LAUNCH mode
                        Capabilities capabilities = dapServer.getDapClient().getCapabilities();
                        boolean supportsTerminateRequest = capabilities != null
                                && Boolean.TRUE.equals(capabilities.getSupportsTerminateRequest());
                        boolean isLaunchMode = launchConfiguration != null
                                && "launch".equals(launchConfiguration.get("request"));

                        boolean shouldSendTerminateRequest = !sentTerminateRequest
                                && supportsTerminateRequest
                                && isLaunchMode;

                        if (shouldSendTerminateRequest) {
                            sentTerminateRequest = true;
                            server.terminate(new TerminateArguments()).whenComplete((r, e) -> {
                                if (e != null) {
                                    LOG.debugf("Terminate error: %s", e.getMessage());
                                }
                            });
                        } else {
                            DisconnectArguments args = new DisconnectArguments();
                            server.disconnect(args).whenComplete((r, e) -> {
                                if (e != null) {
                                    LOG.debugf("Disconnect error (expected if server terminated): %s", e.getMessage());
                                }
                            });
                        }
                    }

                    return CompletableFuture.runAsync(() -> dapServer.stop());
                })
                .handle((result, error) -> {
                    // Always mark as terminated and clean up
                    setState(SessionState.TERMINATED);
                    childSessions.clear();

                    if (error != null) {
                        LOG.errorf(error, "Error during session termination: %s", sessionId);
                        if (traceCollector.isEnabled()) {
                            traceCollector.addTrace(
                                    workspace.getNormalizedUri(),
                                    getContextId(),
                                    "⚠️ Termination error: " + error.getMessage()
                            );
                        }
                    } else {
                        LOG.infof("DAP session terminated: %s", sessionId);
                    }

                    return null;
                });
    }

    // ========== Breakpoints ==========

    /**
     * Send breakpoints for a specific file to the DAP server.
     * This replaces ALL breakpoints for the file (DAP setBreakpoints semantics).
     *
     * @param file the source file path
     * @return CompletableFuture that completes when breakpoints are sent
     */
    private CompletableFuture<Void> sendBreakpoints(String file) {
        IDebugProtocolServer server = dapServer.getDebugServer();
        if (server == null) {
            LOG.warnf("Cannot send breakpoints for %s: server not initialized", file);
            return CompletableFuture.completedFuture(null);
        }

        // Get ALL breakpoints for this file
        List<BreakpointInfo> fileBreakpoints = breakpoints.values().stream()
                .filter(bp -> bp.file.equals(file))
                .toList();

        LOG.infof("Sending %d breakpoint(s) for file: %s", fileBreakpoints.size(), file);

        // Convert to SourceBreakpoint[]
        SourceBreakpoint[] sourceBps = fileBreakpoints.stream()
                .map(info -> {
                    SourceBreakpoint bp = new SourceBreakpoint();
                    bp.setLine(info.line);
                    if (info.condition != null && !info.condition.isEmpty()) {
                        bp.setCondition(info.condition);
                    }
                    return bp;
                })
                .toArray(SourceBreakpoint[]::new);

        // Resolve file path to absolute if needed
        String absolutePath = resolveFilePath(file);

        // Build setBreakpoints request
        SetBreakpointsArguments args = new SetBreakpointsArguments();
        Source source = new Source();
        source.setPath(absolutePath);  // Use absolute path
        args.setSource(source);
        args.setBreakpoints(sourceBps);

        // Send and update verified status
        return server.setBreakpoints(args)
                .thenAccept(response -> {
                    if (response.getBreakpoints() != null) {
                        Breakpoint[] dapBps = response.getBreakpoints();
                        LOG.infof("setBreakpoints response: %d breakpoints returned for %s", dapBps.length, absolutePath);
                        for (int i = 0; i < Math.min(dapBps.length, fileBreakpoints.size()); i++) {
                            BreakpointInfo info = fileBreakpoints.get(i);
                            Breakpoint dapBp = dapBps[i];
                            info.verified = dapBp.isVerified();
                            info.dapBreakpoint = dapBp;
                            LOG.infof("Breakpoint %s at %s:%d verified=%s (id=%s, message=%s)",
                                    info.breakpointId, info.file, info.line, info.verified,
                                    dapBp.getId(), dapBp.getMessage());
                        }
                    } else {
                        LOG.warnf("setBreakpoints returned null breakpoints array for %s", absolutePath);
                    }
                })
                .exceptionally(t -> {
                    LOG.errorf(t, "Failed to send breakpoints for file: %s", file);
                    return null;
                });
    }

    /**
     * Resolve file path to absolute path with platform-native separators.
     * If the path is already absolute, normalizes separators (e.g., forward slashes to backslashes on Windows).
     * If relative, resolves it against the workspace root.
     */
    private String resolveFilePath(String file) {
        // Check if already absolute
        java.nio.file.Path path = java.nio.file.Paths.get(file);
        if (path.isAbsolute()) {
            // Normalize to platform-native separators (important for java-debug path matching on Windows)
            return path.toString();
        }

        // Resolve against workspace root
        try {
            String workspaceFolder = getWorkspaceFolderPath();
            java.nio.file.Path absolutePath = java.nio.file.Paths.get(workspaceFolder, file);
            String resolved = absolutePath.toString();
            LOG.infof("Resolved relative path '%s' to absolute: '%s'", file, resolved);
            return resolved;
        } catch (Exception e) {
            LOG.warnf(e, "Failed to resolve file path: %s, using as-is", file);
            return file;
        }
    }

    /**
     * Send ALL parent's breakpoints to a specific debug server (like lsp4ij pattern).
     * This is used when a child session is created - it receives all breakpoints from the parent.
     *
     * @param server the debug server to send breakpoints to (typically a child server)
     * @return CompletableFuture that completes when all breakpoints are sent
     */
    private CompletableFuture<Void> sendAllBreakpointsToServer(IDebugProtocolServer server) {
        LOG.infof("Sending %d parent breakpoints to specific server", breakpoints.size());

        if (breakpoints.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        // Group breakpoints by file (like lsp4ij does)
        Map<String, List<BreakpointInfo>> byFile = breakpoints.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(bp -> bp.file));

        // Send each file's breakpoints to the given server (like lsp4ij pattern)
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map.Entry<String, List<BreakpointInfo>> entry : byFile.entrySet()) {
            String file = entry.getKey();
            List<BreakpointInfo> fileBps = entry.getValue();

            // Convert to SourceBreakpoint[]
            SourceBreakpoint[] sourceBps = fileBps.stream()
                    .map(info -> {
                        SourceBreakpoint bp = new SourceBreakpoint();
                        bp.setLine(info.line);
                        if (info.condition != null && !info.condition.isEmpty()) {
                            bp.setCondition(info.condition);
                        }
                        return bp;
                    })
                    .toArray(SourceBreakpoint[]::new);

            // Build setBreakpoints request
            SetBreakpointsArguments args = new SetBreakpointsArguments();
            Source source = new Source();
            source.setPath(file);
            args.setSource(source);
            args.setBreakpoints(sourceBps);

            // Send to the specific server (like lsp4ij: setBreakpoints(server, arguments))
            CompletableFuture<Void> future = server.setBreakpoints(args)
                    .thenAccept(response -> {
                        LOG.infof("Server received %d breakpoints for file: %s",
                                response.getBreakpoints() != null ? response.getBreakpoints().length : 0, file);
                    })
                    .exceptionally(t -> {
                        LOG.errorf(t, "Failed to send breakpoints for file: %s", file);
                        return null;
                    });

            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Send ALL breakpoints to the DAP server, grouped by file.
     * Used during initialization (after 'initialized' event, before 'configurationDone').
     *
     * @return CompletableFuture that completes when all breakpoints are sent
     */
    public CompletableFuture<Void> sendAllBreakpoints() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // Send source breakpoints grouped by file
        if (!breakpoints.isEmpty()) {
            Set<String> files = breakpoints.values().stream()
                    .map(bp -> bp.file)
                    .collect(java.util.stream.Collectors.toSet());

            LOG.infof("Sending breakpoints for %d file(s): %s", files.size(), files);

            files.stream()
                    .map(this::sendBreakpoints)
                    .forEach(futures::add);
        }

        // Send instruction breakpoints if adapter supports them
        if (!instructionBreakpoints.isEmpty()) {
            Capabilities caps = dapServer.getDapClient() != null
                    ? dapServer.getDapClient().getCapabilities() : null;
            if (caps != null && Boolean.TRUE.equals(caps.getSupportsInstructionBreakpoints())) {
                LOG.infof("Sending %d instruction breakpoint(s)", instructionBreakpoints.size());
                futures.add(sendInstructionBreakpoints());
            }
        }

        if (futures.isEmpty()) {
            LOG.infof("No breakpoints to send");
            return CompletableFuture.completedFuture(null);
        }

        return trackFuture(CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])));
    }

    /**
     * Set a breakpoint at a specific file and line.
     * This adds the breakpoint to the session and immediately sends ALL breakpoints
     * for the file to the DAP server (since setBreakpoints replaces all breakpoints).
     */
    public CompletableFuture<BreakpointInfo> setBreakpoint(String file, int line, String condition) {
        String breakpointId = UUID.randomUUID().toString();
        BreakpointInfo info = new BreakpointInfo(breakpointId, file, line, condition);
        breakpoints.put(breakpointId, info);

        LOG.infof("Added breakpoint: %s at %s:%d", breakpointId, file, line);

        // Send ALL breakpoints for this file (DAP setBreakpoints replaces all)
        return trackFuture(sendBreakpoints(file).thenApply(v -> info));
    }

    /**
     * Remove a breakpoint.
     * This removes the breakpoint from the session and sends the updated list
     * for the file to the DAP server.
     */
    public CompletableFuture<Boolean> removeBreakpoint(String breakpointId) {
        BreakpointInfo info = breakpoints.remove(breakpointId);
        if (info == null) {
            return CompletableFuture.completedFuture(false);
        }

        LOG.infof("Removed breakpoint: %s at %s:%d", breakpointId, info.file, info.line);

        // Send ALL remaining breakpoints for this file (empty list if none left)
        return trackFuture(sendBreakpoints(info.file).thenApply(v -> true));
    }

    /**
     * List all breakpoints.
     */
    public List<BreakpointInfo> listBreakpoints() {
        return new ArrayList<>(breakpoints.values());
    }

    // ========== Instruction Breakpoints ==========

    /**
     * Set an instruction breakpoint at a memory address.
     */
    public CompletableFuture<InstructionBreakpointInfo> setInstructionBreakpoint(
            String instructionReference, Integer offset, String condition) {
        String breakpointId = UUID.randomUUID().toString();
        InstructionBreakpointInfo info = new InstructionBreakpointInfo(
                breakpointId, instructionReference, offset, condition);
        instructionBreakpoints.put(breakpointId, info);

        LOG.infof("Added instruction breakpoint: %s at %s (offset=%s)", breakpointId, instructionReference, offset);

        return trackFuture(sendInstructionBreakpoints().thenApply(v -> info));
    }

    /**
     * Remove an instruction breakpoint.
     */
    public CompletableFuture<Boolean> removeInstructionBreakpoint(String breakpointId) {
        InstructionBreakpointInfo info = instructionBreakpoints.remove(breakpointId);
        if (info == null) {
            return CompletableFuture.completedFuture(false);
        }

        LOG.infof("Removed instruction breakpoint: %s at %s", breakpointId, info.instructionReference);

        return trackFuture(sendInstructionBreakpoints().thenApply(v -> true));
    }

    /**
     * List all instruction breakpoints.
     */
    public List<InstructionBreakpointInfo> listInstructionBreakpoints() {
        return new ArrayList<>(instructionBreakpoints.values());
    }

    /**
     * Send all instruction breakpoints to the DAP server.
     * Unlike source breakpoints (per-file), setInstructionBreakpoints replaces ALL instruction breakpoints at once.
     */
    private CompletableFuture<Void> sendInstructionBreakpoints() {
        IDebugProtocolServer server = getActiveServer();
        if (server == null) {
            LOG.warnf("Cannot send instruction breakpoints: server not initialized");
            return CompletableFuture.completedFuture(null);
        }

        List<InstructionBreakpointInfo> allInstrBps = new ArrayList<>(instructionBreakpoints.values());

        LOG.infof("Sending %d instruction breakpoint(s)", allInstrBps.size());

        SetInstructionBreakpointsArguments args = new SetInstructionBreakpointsArguments();
        InstructionBreakpoint[] instrBps = allInstrBps.stream()
                .map(info -> {
                    InstructionBreakpoint bp = new InstructionBreakpoint();
                    bp.setInstructionReference(info.instructionReference);
                    if (info.offset != null) {
                        bp.setOffset(info.offset);
                    }
                    if (info.condition != null && !info.condition.isEmpty()) {
                        bp.setCondition(info.condition);
                    }
                    return bp;
                })
                .toArray(InstructionBreakpoint[]::new);
        args.setBreakpoints(instrBps);

        return server.setInstructionBreakpoints(args)
                .thenAccept(response -> {
                    if (response.getBreakpoints() != null) {
                        Breakpoint[] dapBps = response.getBreakpoints();
                        for (int i = 0; i < Math.min(dapBps.length, allInstrBps.size()); i++) {
                            InstructionBreakpointInfo info = allInstrBps.get(i);
                            Breakpoint dapBp = dapBps[i];
                            info.verified = dapBp.isVerified();
                            info.dapBreakpoint = dapBp;
                            LOG.infof("Instruction breakpoint %s at %s verified=%s",
                                    info.breakpointId, info.instructionReference, info.verified);
                        }
                    }
                })
                .exceptionally(t -> {
                    LOG.errorf(t, "Failed to send instruction breakpoints");
                    return null;
                });
    }

    // ========== Execution Control ==========

    /**
     * Continue execution.
     */
    public CompletableFuture<Map<String, Object>> continueExecution() {
        LOG.infof("Continue execution: %s", sessionId);

        IDebugProtocolServer server = getActiveServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to continue"));
        }

        ContinueArguments args = new ContinueArguments();
        args.setThreadId(currentThreadId);

        return trackFuture(server.continue_(args)
                .thenApply(response -> {
                    setState(SessionState.RUNNING);
                    return createResultWithOutput(Map.of(
                            "success", true,
                            "allThreadsContinued", response.getAllThreadsContinued() != null ? response.getAllThreadsContinued() : false
                    ));
                }));
    }

    /**
     * Step over (next line).
     */
    public CompletableFuture<Map<String, Object>> stepOver() {
        return stepOver(null);
    }

    /**
     * Step over with optional stepping granularity.
     */
    public CompletableFuture<Map<String, Object>> stepOver(SteppingGranularity granularity) {
        LOG.infof("Step over: %s (granularity=%s)", sessionId, granularity);

        IDebugProtocolServer server = getActiveServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to step"));
        }

        NextArguments args = new NextArguments();
        args.setThreadId(currentThreadId);
        args.setGranularity(granularity);

        return trackFuture(server.next(args)
                .thenApply(v -> createResultWithOutput(Map.of("success", true))));
    }

    /**
     * Step into function.
     */
    public CompletableFuture<Map<String, Object>> stepIn() {
        return stepIn(null);
    }

    /**
     * Step into with optional stepping granularity.
     */
    public CompletableFuture<Map<String, Object>> stepIn(SteppingGranularity granularity) {
        LOG.infof("Step in: %s (granularity=%s)", sessionId, granularity);

        IDebugProtocolServer server = getActiveServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to step"));
        }

        StepInArguments args = new StepInArguments();
        args.setThreadId(currentThreadId);
        args.setGranularity(granularity);

        return trackFuture(server.stepIn(args)
                .thenApply(v -> createResultWithOutput(Map.of("success", true))));
    }

    /**
     * Step out of current function.
     */
    public CompletableFuture<Map<String, Object>> stepOut() {
        return stepOut(null);
    }

    /**
     * Step out with optional stepping granularity.
     */
    public CompletableFuture<Map<String, Object>> stepOut(SteppingGranularity granularity) {
        LOG.infof("Step out: %s (granularity=%s)", sessionId, granularity);

        IDebugProtocolServer server = getActiveServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to step"));
        }

        StepOutArguments args = new StepOutArguments();
        args.setThreadId(currentThreadId);
        args.setGranularity(granularity);

        return trackFuture(server.stepOut(args)
                .thenApply(v -> createResultWithOutput(Map.of("success", true))));
    }

    /**
     * Helper to create a result Map with console output included.
     */
    private Map<String, Object> createResultWithOutput(Map<String, Object> baseResult) {
        Map<String, Object> result = new HashMap<>(baseResult);

        // Include console output if any
        if (programOutput.hasOutput()) {
            result.put("consoleOutput", programOutput.getAllWithCategories());
            result.put("outputLines", programOutput.getLineCount());
        }

        return result;
    }

    /**
     * Pause execution.
     */
    public CompletableFuture<Void> pause() {
        LOG.infof("Pause execution: %s", sessionId);

        IDebugProtocolServer server = getActiveServer();
        if (server == null || currentThreadId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No thread to pause"));
        }

        PauseArguments args = new PauseArguments();
        args.setThreadId(currentThreadId);

        return trackFuture(server.pause(args)
                .thenAccept(v -> setState(SessionState.PAUSED)));
    }

    // ========== Inspection ==========

    /**
     * Get the active debug server.
     * Like lsp4ij: when a child session sends 'stopped', that child's server becomes the active one.
     * All subsequent requests (stackTrace, continue, step, etc.) use the active server.
     */
    private IDebugProtocolServer getActiveServer() {
        return activeServer != null ? activeServer : dapServer.getDebugServer();
    }

    /**
     * Get stack trace for current thread.
     */
    public CompletableFuture<StackFrame[]> getStackTrace() {
        LOG.infof("Get stack trace: %s (activeServer=%s, currentThreadId=%d)",
                sessionId, activeServer != null ? "child" : "parent", currentThreadId);

        // Like lsp4ij: use the active server (the one that sent the last 'stopped' event)
        // For VS Code JS Debug, child sessions have the threads, not the parent
        IDebugProtocolServer server = activeServer != null ? activeServer : dapServer.getDebugServer();
        if (server == null || currentThreadId == null) {
            LOG.warnf("Cannot get stack trace: server=%s, currentThreadId=%s", server, currentThreadId);
            return CompletableFuture.completedFuture(new StackFrame[0]);
        }

        StackTraceArguments args = new StackTraceArguments();
        args.setThreadId(currentThreadId);

        return trackFuture(server.stackTrace(args)
                .thenApply(response -> {
                    currentStackFrames = response.getStackFrames();
                    return currentStackFrames;
                }));
    }

    /**
     * Get threads list.
     */
    public CompletableFuture<Thread[]> getThreads() {
        LOG.infof("Get threads: %s", sessionId);

        IDebugProtocolServer server = getActiveServer();
        if (server == null) {
            return CompletableFuture.completedFuture(new Thread[0]);
        }

        return trackFuture(server.threads()
                .thenApply(response -> {
                    threads = response.getThreads();
                    if (threads.length > 0 && currentThreadId == null) {
                        currentThreadId = threads[0].getId();
                    }
                    return threads;
                }));
    }

    /**
     * Get scopes for a stack frame.
     */
    public CompletableFuture<Scope[]> getScopes(int frameId) {
        LOG.infof("Get scopes for frame %d: %s", frameId, sessionId);

        IDebugProtocolServer server = getActiveServer();
        if (server == null) {
            return CompletableFuture.completedFuture(new Scope[0]);
        }

        ScopesArguments args = new ScopesArguments();
        args.setFrameId(frameId);

        return trackFuture(server.scopes(args)
                .thenApply(ScopesResponse::getScopes));
    }

    /**
     * Get variables from a scope or variable reference.
     */
    public CompletableFuture<Variable[]> getVariables(int variablesReference) {
        LOG.infof("Get variables for reference %d: %s", variablesReference, sessionId);

        IDebugProtocolServer server = getActiveServer();
        if (server == null) {
            return CompletableFuture.completedFuture(new Variable[0]);
        }

        VariablesArguments args = new VariablesArguments();
        args.setVariablesReference(variablesReference);

        return trackFuture(server.variables(args)
                .thenApply(VariablesResponse::getVariables));
    }

    /**
     * Evaluate an expression in the current debug context.
     */
    public CompletableFuture<EvaluateResponse> evaluate(String expression, Integer frameId) {
        LOG.infof("Evaluate expression '%s': %s", expression, sessionId);

        IDebugProtocolServer server = getActiveServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("DAP server not initialized"));
        }

        EvaluateArguments args = new EvaluateArguments();
        args.setExpression(expression);
        if (frameId != null) {
            args.setFrameId(frameId);
        }
        args.setContext("watch");

        return trackFuture(server.evaluate(args));
    }

    // ========== Disassembly ==========

    /**
     * Disassemble instructions at a memory address.
     */
    public CompletableFuture<DisassembledInstruction[]> disassemble(
            String memoryReference, Integer offset, Integer instructionOffset, Integer instructionCount) {
        LOG.infof("Disassemble at %s: %s", memoryReference, sessionId);

        IDebugProtocolServer server = getActiveServer();
        if (server == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("DAP server not initialized"));
        }

        DisassembleArguments args = new DisassembleArguments();
        args.setMemoryReference(memoryReference);
        if (offset != null) {
            args.setOffset(offset);
        }
        if (instructionOffset != null) {
            args.setInstructionOffset(instructionOffset);
        }
        args.setInstructionCount(instructionCount != null ? instructionCount : 50);
        args.setResolveSymbols(true);

        return trackFuture(server.disassemble(args)
                .thenApply(DisassembleResponse::getInstructions));
    }

    // ========== Event Handlers (DapEventListener implementation) ==========

    @Override
    public void onInitialized() {
        LOG.infof("Session %s initialized event received", sessionId);
        // Server is now ready to receive configuration (breakpoints, etc.)
    }

    @Override
    public void onStopped(StoppedEventArguments event) {
        LOG.infof("Session %s stopped: %s (thread %d)", sessionId, event.getReason(), event.getThreadId());
        setState(SessionState.PAUSED);
        currentThreadId = event.getThreadId();
    }

    @Override
    public void onContinued(ContinuedEventArguments event) {
        LOG.infof("Session %s continued (thread %d)", sessionId, event.getThreadId());
        setState(SessionState.RUNNING);
    }

    @Override
    public void onExited(ExitedEventArguments event) {
        LOG.infof("Session %s exited with code: %d", sessionId, event.getExitCode());
        setState(SessionState.TERMINATED);
    }

    @Override
    public void onTerminated(TerminatedEventArguments event) {
        LOG.infof("Session %s terminated (isChild=%s)", sessionId, isChildSession);
        setState(SessionState.TERMINATED);

        // DON'T stop the server here (like lsp4ij)!
        // If terminate() is in progress, it will call stop2() after disconnect() completes
        // Closing the socket here would prevent disconnect() from receiving its response
    }

    @Override
    public void onThread(ThreadEventArguments event) {
        LOG.infof("Session %s thread event: %s (thread %d)", sessionId, event.getReason(), event.getThreadId());
    }

    @Override
    public void onOutput(OutputEventArguments event) {
        String category = event.getCategory();
        String output = event.getOutput();

        LOG.debugf("Session %s output [%s]: %s", sessionId, category, output);

        // Capture program output for AI agent
        // Skip telemetry - it's just internal DAP metrics
        if (!"telemetry".equals(category)) {
            programOutput.addOutput(event);
        }

        // Send to trace collector for UI visibility
        if (traceCollector.isEnabled() && output != null && !output.isBlank()) {
            // Skip telemetry outputs - they're just internal DAP metrics
            if ("telemetry".equals(category)) {
                return;
            }

            // For console/stdout/stderr, send just the output without trace prefix
            // These are ALWAYS shown, regardless of trace level
            String displayText;
            TraceCollector.MessageType messageType = TraceCollector.MessageType.INFO;

            if ("console".equals(category)) {
                displayText = output.trim();  // Console output (blue via messageType)
                messageType = TraceCollector.MessageType.INFO;
            } else if ("stdout".equals(category)) {
                displayText = output.trim();  // Standard output (cyan via messageType)
                messageType = TraceCollector.MessageType.INFO;
            } else if ("stderr".equals(category)) {
                displayText = output.trim();  // Error output (red via messageType)
                messageType = TraceCollector.MessageType.ERROR;
            } else {
                displayText = String.format("[DAP Output - %s] %s", category, output.trim());
                messageType = TraceCollector.MessageType.TRACE;
            }

            traceCollector.addTrace(
                    workspace.getNormalizedUri(),
                    getContextId(),
                    displayText,
                    messageType
            );
        }
    }

    @Override
    public void onBreakpoint(BreakpointEventArguments event) {
        LOG.infof("Session %s breakpoint event: reason=%s, id=%s, verified=%s, message=%s",
                sessionId, event.getReason(),
                event.getBreakpoint() != null ? event.getBreakpoint().getId() : "null",
                event.getBreakpoint() != null ? event.getBreakpoint().isVerified() : "null",
                event.getBreakpoint() != null ? event.getBreakpoint().getMessage() : "null");
        // Update breakpoint verification status if needed
        if (event.getBreakpoint() != null) {
            Breakpoint bp = event.getBreakpoint();
            boolean matched = false;
            // Find and update corresponding BreakpointInfo
            for (BreakpointInfo info : breakpoints.values()) {
                if (info.dapBreakpoint != null && info.dapBreakpoint.getId() != null
                        && info.dapBreakpoint.getId().equals(bp.getId())) {
                    info.verified = bp.isVerified();
                    info.dapBreakpoint = bp;
                    matched = true;
                    LOG.infof("Breakpoint %s matched and updated: verified=%s", info.breakpointId, info.verified);
                    break;
                }
            }
            if (!matched) {
                LOG.warnf("Breakpoint event id=%s did not match any stored breakpoint (stored breakpoints: %d)",
                        bp.getId(), breakpoints.size());
                for (BreakpointInfo info : breakpoints.values()) {
                    LOG.warnf("  Stored breakpoint %s: dapBreakpoint=%s, dapBreakpointId=%s",
                            info.breakpointId,
                            info.dapBreakpoint != null ? "present" : "null",
                            info.dapBreakpoint != null ? info.dapBreakpoint.getId() : "null");
                }
            }
        }
    }

    @Override
    public void onModule(ModuleEventArguments event) {
        LOG.debugf("Session %s module event: %s", sessionId, event.getReason());
    }

    @Override
    public void onLoadedSource(LoadedSourceEventArguments event) {
        LOG.debugf("Session %s loaded source: %s", sessionId, event.getReason());
    }

    @Override
    public void onProcess(ProcessEventArguments event) {
        LOG.infof("Session %s process event: %s", sessionId, event.getName());
    }

    @Override
    public void onCapabilities(CapabilitiesEventArguments event) {
        LOG.debugf("Session %s capabilities changed", sessionId);
    }

    @Override
    public void onProgressStart(ProgressStartEventArguments event) {
        LOG.debugf("Session %s progress start: %s", sessionId, event.getTitle());
    }

    @Override
    public void onProgressUpdate(ProgressUpdateEventArguments event) {
        LOG.debugf("Session %s progress update: %s", sessionId, event.getMessage());
    }

    @Override
    public void onProgressEnd(ProgressEndEventArguments event) {
        LOG.debugf("Session %s progress end", sessionId);
    }

    @Override
    public void onInvalidated(InvalidatedEventArguments event) {
        LOG.debugf("Session %s invalidated: %s", sessionId, event.getAreas());
        // Clear cached stack frames when invalidated
        currentStackFrames = new StackFrame[0];
    }

    @Override
    public void onMemory(MemoryEventArguments event) {
        LOG.debugf("Session %s memory changed: %s", sessionId, event.getMemoryReference());
    }

    // ========== Variable Resolution ==========

    /**
     * Resolve variables in DAP configuration (like lsp4ij).
     * Supports: ${workspaceFolder}, ${file}, ${fileBasename}, etc.
     */
    private Map<String, Object> resolveVariables(Map<String, Object> config) {
        Map<String, Object> resolved = new HashMap<>();
        String workspaceFolder = getWorkspaceFolderPath();

        for (Map.Entry<String, Object> entry : config.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String str) {
                // Resolve ${workspaceFolder}
                str = str.replace("${workspaceFolder}", workspaceFolder);
                // Resolve ${workspaceRoot} (deprecated alias)
                str = str.replace("${workspaceRoot}", workspaceFolder);
                resolved.put(entry.getKey(), str);
            } else if (value instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) value;
                List<Object> resolvedList = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String str) {
                        str = str.replace("${workspaceFolder}", workspaceFolder);
                        str = str.replace("${workspaceRoot}", workspaceFolder);
                        resolvedList.add(str);
                    } else {
                        resolvedList.add(item);
                    }
                }
                resolved.put(entry.getKey(), resolvedList);
            } else {
                resolved.put(entry.getKey(), value);
            }
        }

        return resolved;
    }

    /**
     * Get workspace folder path as a proper file system path (not URI).
     * On Windows: "C:\path\to\workspace" (not "file:/C:/path/to/workspace")
     */
    private String getWorkspaceFolderPath() {
        try {
            // Convert URI to Path to get proper file system path
            java.nio.file.Path path = java.nio.file.Paths.get(workspace.getRootUri());
            return path.toString();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to convert workspace URI to path: %s", workspace.getRootUri());
            // Fallback to normalized URI
            return workspace.getNormalizedUri();
        }
    }

    // ========== Getters ==========

    public String getSessionId() {
        return sessionId;
    }

    public String getLanguage() {
        return language;
    }

    public String getSessionName() {
        return sessionName;
    }

    public SessionActor getCreatedBy() {
        return createdBy;
    }

    public java.time.Instant getCreatedAt() {
        return createdAt;
    }

    public SessionActor getLaunchedBy() {
        return launchedBy;
    }

    public Instant getLaunchedAt() {
        return launchedAt;
    }

    public void setLaunchedBy(SessionActor launchedBy) {
        this.launchedBy = launchedBy;
    }

    public SessionState getState() {
        return state;
    }

    public Map<String, Object> getLaunchConfiguration() {
        return launchConfiguration;
    }

    public boolean isAttach() {
        return attachMode;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    /**
     * Set the callback to be called when session state changes.
     */
    public void setStateChangeCallback(Runnable callback) {
        this.stateChangeCallback = callback;
    }

    /**
     * Update session state and notify listeners.
     */
    private void setState(SessionState newState) {
        setState(newState, null);
    }

    private void setState(SessionState newState, String errorMessage) {
        if (this.state != newState) {
            LOG.infof("Session %s state change: %s -> %s", sessionId, this.state, newState);
            this.state = newState;
            if (isErrorState(newState)) {
                this.errorMessage = errorMessage;
            } else {
                this.errorMessage = null;
            }

            // Notify callback
            if (stateChangeCallback != null) {
                stateChangeCallback.run();
            }
        }
    }

    /**
     * Returns the session-level error message (e.g., launch failed because program doesn't exist).
     * For server-level errors (e.g., DAP server process crashed), see {@link ServerBase#getErrorMessage()}.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns true if the given state represents an error condition
     * (ERROR, LAUNCH_FAILED, ATTACH_FAILED).
     */
    private static boolean isErrorState(SessionState state) {
        return state == SessionState.ERROR
                || state == SessionState.LAUNCH_FAILED
                || state == SessionState.ATTACH_FAILED;
    }

    public DapServer getDapServer() {
        return dapServer;
    }

    public DapServerConfig getServerConfig() {
        return dapServer != null ? dapServer.getConfig() : null;
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    /**
     * Get the program output buffer for this session.
     * Contains stdout/stderr from the debugged program.
     */
    public DapProgramOutput getProgramOutput() {
        return programOutput;
    }
}
