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
package com.ibm.mcp.languagetools.server;

import com.ibm.mcp.languagetools.dap.session.DapSession;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.configuration.ServerTrace;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.trace.TracingMessageConsumer;
import com.ibm.mcp.languagetools.utils.OSUtils;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Base class for server implementations (LSP and DAP).
 *
 * <p>Manages the full server lifecycle shared by both language servers
 * ({@link com.ibm.mcp.languagetools.lsp.server.LspServer}) and debug adapters
 * ({@link com.ibm.mcp.languagetools.dap.server.DapServer}):</p>
 * <ul>
 *   <li><b>Process management</b>: launching, monitoring stderr, and destroying
 *       the OS process ({@link #startProcess()}, {@link #destroyProcess})</li>
 *   <li><b>Status lifecycle</b>: NOT_STARTED → STARTING → RUNNING → STOPPED,
 *       with listener notifications on every transition ({@link #setStatus})</li>
 *   <li><b>Readiness gates</b>: {@link #waitForStarted()} / {@link #waitForReady()}
 *       for callers that need the server to be operational before sending requests</li>
 *   <li><b>Tracing</b>: wire-level message tracing via {@link com.ibm.mcp.languagetools.trace.TracingMessageConsumer}
 *       and application-level trace messages via {@link #addTrace}</li>
 *   <li><b>Inter-server routing</b>: inherited from {@link ServerRequestRouter},
 *       allowing any server to delegate requests to another LSP server
 *       (e.g., java-debug DAP → JDTLS)</li>
 * </ul>
 *
 * <p>Subclasses must implement:</p>
 * <ul>
 *   <li>{@link #getServerTrace()} — returns the configured trace level</li>
 *   <li>{@link #initializeTraceCollector} — creates the appropriate trace collector</li>
 * </ul>
 *
 * @param <T> the type of server configuration ({@link com.ibm.mcp.languagetools.lsp.server.LspServerConfig}
 *            or {@link com.ibm.mcp.languagetools.dap.server.DapServerConfig})
 */
public abstract class ServerBase<T extends ServerConfigBase> extends ServerRequestRouter {

    private static final Logger LOG = Logger.getLogger(ServerBase.class);

    private final T config;
    private final Workspace workspace;
    /**
     * Composite context ID for trace messages:
     * <ul>
     *   <li>LSP: the server ID (e.g. "jdtls")</li>
     *   <li>DAP: "serverId#sessionId" (e.g. "js-debug#session-123")</li>
     * </ul>
     */
    private final String traceContextId;
    private ExecutorService executorService;
    private final TraceCollector traceCollector;
    private final TracingMessageConsumer tracing;
    private volatile ServerStatus status = ServerStatus.NOT_STARTED;
    private volatile String statusMessage = null;
    private volatile String errorMessage = null;
    private final List<StatusChangeListener> statusChangeListeners = new CopyOnWriteArrayList<>();

    private Process serverProcess;
    private volatile boolean isReady;
    private volatile boolean isStarted;
    private volatile CompletableFuture<Void> readyFuture;
    private volatile CompletableFuture<Void> startedFuture;
    private volatile OperationEntry operationEntry;

    public ServerBase(T config, Workspace workspace) {
        this(config, workspace, config.getServerId());
    }

    /**
     * Constructor with explicit context ID for tracing.
     * <p>
     * The traceContextId identifies the trace source:
     * <ul>
     *   <li>LSP: the server ID (e.g. "jdtls")</li>
     *   <li>DAP: "serverId#sessionId" (e.g. "js-debug#session-123")</li>
     * </ul>
     */
    protected ServerBase(T config, Workspace workspace, String traceContextId) {
        super(config, workspace);
        this.config = config;
        this.workspace = workspace;
        this.traceContextId = traceContextId;
        this.executorService = new ThreadPoolExecutor(2, 8, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        this.readyFuture = new CompletableFuture<>();
        this.startedFuture = new CompletableFuture<>();

        var workspaceRoot = workspace.getNormalizedUri();
        this.traceCollector = initializeTraceCollector(workspace);
        this.tracing = new TracingMessageConsumer(traceCollector, workspaceRoot, traceContextId);
    }

    /**
     * Returns the trace context ID that identifies this server instance in trace messages.
     */
    public final String getTraceContextId() {
        return traceContextId;
    }

    /**
     * Returns the executor service used for background tasks (stderr monitoring, async operations).
     */
    protected ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Returns the OS process running the server, or {@code null} if not started.
     */
    protected Process getServerProcess() {
        return serverProcess;
    }

    /**
     * Start the OS process for this server using the configured command,
     * environment variables, and working directory.
     *
     * @return the started process
     * @throws IOException if the command is not configured or the process fails to start
     */
    protected Process startProcess() throws IOException {
        var config = getConfig();
        List<String> command = buildCommand();
        String commandStr = String.join(" ", command);

        addTrace(String.format("Starting %s...", config.getName()));
        addTrace(String.format("Command: %s", commandStr));

        ProcessBuilder pb = new ProcessBuilder(command);

        if (config.getEnv() != null && !config.getEnv().isEmpty()) {
            pb.environment().putAll(config.getEnv());
        }

        String workingDir = getWorkingDirectory();
        if (workingDir != null) {
            String resolvedWorkingDir = ServerVariables.resolve(workingDir, config);
            pb.directory(Paths.get(resolvedWorkingDir).toFile());
            addTrace(String.format("Working directory: %s", resolvedWorkingDir));
        }

        this.serverProcess = pb.start();
        addTrace(String.format("Server process started (PID: %d)", serverProcess.pid()));
        startStderrMonitoring();
        startProcessExitMonitoring();
        return this.serverProcess;
    }

    /**
     * Get the working directory for this server process.
     * Subclasses can override to provide a default (e.g., BSP uses workspace root).
     */
    protected String getWorkingDirectory() {
        return getConfig().getWorkingDirectory();
    }

    /**
     * Build the command line arguments to launch the server.
     * Subclasses can override to add variable substitution (e.g., {@code ${port}} in DAP).
     */
    protected List<String> buildCommand() throws IOException {
        String cmd = getConfig().getCommand();
        if (cmd == null) {
            throw new IOException("No command configured for current OS");
        }
        List<String> args = parseCommandLine(cmd);
        if (OSUtils.isWindows() && !args.isEmpty()) {
            String exe = args.get(0).toLowerCase();
            if (exe.endsWith(".bat") || exe.endsWith(".cmd")) {
                args.add(0, "cmd.exe");
                args.add(1, "/c");
            }
        }
        return args;
    }

    /**
     * Parse a command line string into a list of arguments, handling quoted strings.
     */
    protected List<String> parseCommandLine(String commandLine) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (!current.isEmpty()) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            args.add(current.toString());
        }

        return args;
    }

    /**
     * Returns the PID of the server process, or {@code null} if not alive.
     */
    public Long getPid() {
        Process process = this.serverProcess;
        return process != null && process.isAlive() ? process.pid() : null;
    }

    /**
     * Add a trace message using the stored workspace URI and context ID.
     */
    protected void addTrace(String content) {
        if (!traceCollector.isEnabled()) {
            return;
        }
        traceCollector.addTrace(workspace.getNormalizedUri(), traceContextId, content);
    }

    /**
     * Add a trace message with a specific message type.
     */
    protected void addTrace(String content, TraceCollector.MessageType messageType) {
        if (!traceCollector.isEnabled()) {
            return;
        }
        traceCollector.addTrace(workspace.getNormalizedUri(), traceContextId, content, messageType);
    }

    /**
     * Get the server configuration.
     */
    public T getConfig() {
        return config;
    }

    /**
     * Returns the server installation directory.
     */
    public Path getServerHome() {
        return config.getServerHome();
    }
    /**
     * Get the current server status.
     */
    public final ServerStatus getStatus() {
        return status;
    }

    /**
     * Returns the unique server identifier (e.g. "jdtls", "java-debug").
     */
    public String getId() {
        return config.getServerId();
    }

    /**
     * Functional interface for status change listener that receives both old and new status.
     */
    @FunctionalInterface
    public interface StatusChangeListener {
        void onStatusChanged(ServerStatus oldStatus, ServerStatus newStatus);
    }

    /**
     * Add a listener to be notified when server status changes.
     */
    public void addStatusChangeListener(StatusChangeListener listener) {
        if (listener != null) {
            statusChangeListeners.add(listener);
        }
    }

    /**
     * Remove a status change listener.
     */
    public void removeStatusChangeListener(StatusChangeListener listener) {
        statusChangeListeners.remove(listener);
    }

    /**
     * Update server status and notify all listeners.
     */
    public void setStatus(ServerStatus newStatus) {
        setStatus(newStatus, null);
    }

    /**
     * Update server status with an error message and notify all listeners.
     * The error message is stored when the status is a failure state
     * (ERROR, START_FAILED, INSTALL_FAILED) and cleared otherwise.
     */
    public void setStatus(ServerStatus newStatus, String errorMessage) {
        ServerStatus oldStatus = this.status;
        this.status = newStatus;

        // Store or clear error message based on status
        if (newStatus == ServerStatus.ERROR
            || newStatus == ServerStatus.START_FAILED
            || newStatus == ServerStatus.INSTALL_FAILED) {
            if (errorMessage != null) {
                this.errorMessage = errorMessage;
            }
        } else {
            this.errorMessage = null;
        }

        // Clear status message when stopping/stopped
        if (newStatus == ServerStatus.STOPPING || newStatus == ServerStatus.STOPPED) {
            this.statusMessage = null;
        }

        LOG.infof("Server.setStatus: %s -> %s (listeners: %d)",
                oldStatus, newStatus, statusChangeListeners.size());

        if (oldStatus != newStatus && !statusChangeListeners.isEmpty()) {
            LOG.infof("Notifying %d listeners for %s: %s -> %s",
                    statusChangeListeners.size(), config.getServerId(), oldStatus, newStatus);
            for (StatusChangeListener listener : statusChangeListeners) {
                try {
                    listener.onStatusChanged(oldStatus, newStatus);
                } catch (Exception e) {
                    LOG.warnf(e, "Error in status change listener for %s", config.getServerId());
                }
            }
        }

        // Cleanup resources when entering terminal states
        if (newStatus == ServerStatus.ERROR
            || newStatus == ServerStatus.START_FAILED
            || newStatus == ServerStatus.STOPPED) {
            cleanupResources();
        }
    }

    /**
     * Returns the server-level error message (e.g., server process crashed, failed to start).
     * For DAP session-level errors (e.g., launch failed because program doesn't exist),
     * see {@link DapSession#getErrorMessage()}.
     */
    public final String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Cleanup resources (threads, processes) when server enters error state.
     * This is called synchronously when setStatus(ERROR) happens, so must be FAST.
     * Can be overridden by subclasses to add custom cleanup.
     */
    protected void cleanupResources() {
        LOG.infof("Cleaning up resources for %s (status: %s)", config.getServerId(), status);

        // Kill the server process if still running
        Process process = getServerProcess();
        if (process != null) {
            if (process.isAlive()) {
                LOG.infof("Destroying server process (PID: %d)", process.pid());
                process.destroyForcibly();
            }
            this.serverProcess = null;
        }

        // DON'T shutdown executor - it would reject future start() attempts
        // Just let monitoring threads die naturally when streams close
        LOG.infof("Resources cleaned for %s (executor kept alive)", config.getServerId());
    }

    /**
     * Returns the human-readable status message (e.g. "Importing Maven projects...").
     */
    public final String getStatusMessage() {
        return statusMessage;
    }

    /**
     * Updates the human-readable status message and notifies listeners if it changed.
     */
    public void setStatusMessage(String statusMessage) {
        String oldMessage = this.statusMessage;
        this.statusMessage = statusMessage;

        LOG.infof("[%s] setStatusMessage called: %s -> %s (listeners: %d)",
                config.getServerId(), oldMessage, statusMessage, statusChangeListeners.size());

        // Notify if message changed and listeners are registered
        if (!Objects.equals(oldMessage, statusMessage) && !statusChangeListeners.isEmpty()) {
            LOG.infof("[%s] Status message changed, notifying %d listeners", config.getServerId(), statusChangeListeners.size());
            // Trigger listeners to refresh UI
            for (StatusChangeListener listener : statusChangeListeners) {
                try {
                    listener.onStatusChanged(this.status, this.status);
                } catch (Exception e) {
                    LOG.warnf(e, "Error in status change listener for %s", config.getServerId());
                }
            }
        }
    }

    /**
     * Start monitoring stderr from the server process.
     * Captures errors and sends them to the trace collector.
     * Uses the stored workspace URI and context ID.
     */
    protected void startStderrMonitoring() {
        Process process = getServerProcess();
        if (process == null) {
            return;
        }
        executorService.submit(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                StringBuilder stackTraceBuffer = new StringBuilder();

                while ((line = reader.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                    LOG.errorf("[%s stderr] %s", config.getServerId(), line);

                    String trimmed = line.trim();
                    boolean isStackTraceLine = trimmed.startsWith("at ") && trimmed.contains("(") && trimmed.contains(")");
                    boolean isExceptionLine = trimmed.contains("Exception:") || trimmed.contains("Error:");

                    if (isStackTraceLine || (isExceptionLine && stackTraceBuffer.isEmpty())) {
                        stackTraceBuffer.append(line).append("\n");
                    } else {
                        if (!stackTraceBuffer.isEmpty()) {
                            addTrace(stackTraceBuffer.toString().trim(), TraceCollector.MessageType.ERROR);
                            stackTraceBuffer.setLength(0);
                        }

                        addTrace(line, TraceCollector.MessageType.ERROR);
                    }
                }

                if (!stackTraceBuffer.isEmpty()) {
                    addTrace(stackTraceBuffer.toString().trim(), TraceCollector.MessageType.ERROR);
                }

                LOG.infof("Stderr monitor for %s ended", config.getServerId());
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    LOG.errorf(e, "Error reading stderr for %s", config.getServerId());
                } else {
                    LOG.infof("Stderr monitor interrupted for %s", config.getServerId());
                }
            } catch (Exception e) {
                LOG.errorf(e, "Unexpected error in stderr monitor for %s", config.getServerId());
            }
        });
    }

    private void startProcessExitMonitoring() {
        Process process = getServerProcess();
        if (process == null) {
            return;
        }
        process.onExit().thenAccept(p -> {
            int exitCode = p.exitValue();
            if (getStatus() == ServerStatus.RUNNING || getStatus() == ServerStatus.STARTING) {
                String message = "Process exited with code " + exitCode;
                addTrace(message, TraceCollector.MessageType.ERROR);
                setStatus(ServerStatus.START_FAILED, message);
            }
        });
    }

    /**
     * Sets the current operation entry for progress tracking in the UI.
     */
    public void setOperationEntry(OperationEntry operationEntry) {
        this.operationEntry = operationEntry;
    }

    /**
     * Returns the current operation entry for progress tracking, or {@code null}.
     */
    public OperationEntry getOperationEntry() {
        return operationEntry;
    }

    /**
     * Returns a future that completes when the server process has started.
     */
    public CompletableFuture<Void> waitForStarted() {
        if (isStarted) {
            return CompletableFuture.completedFuture(null);
        }
        return startedFuture;
    }

    /**
     * Returns a future that completes when the server is fully ready
     * (e.g., after indexing for LSP servers).
     */
    public CompletableFuture<Void> waitForReady() {
        if (isReady) {
            return CompletableFuture.completedFuture(null);
        }
        OperationEntry entry = this.operationEntry;
        OperationEntry indexingChild = entry != null ? entry.addChild("indexing") : null;
        return readyFuture.thenRun(() -> {
            if (indexingChild != null) {
                indexingChild.complete();
            }
        });
    }

    /**
     * Returns {@code true} if the server process has started.
     */
    public final boolean isStarted() {
        return isStarted;
    }

    /**
     * Returns {@code true} if the server is fully operational and ready to handle requests.
     */
    public final boolean isReady() {
        return isReady;
    }

    /**
     * Marks the server as started or not started, completing or resetting the started future.
     */
    public final void setStarted(boolean started) {
        boolean wasStarted = this.isStarted;
        this.isStarted = started;

        if (started && !wasStarted && startedFuture != null && !startedFuture.isDone()) {
            OperationEntry entry = this.operationEntry;
            if (entry != null) {
                OperationEntry startChild = entry.findChild("starting");
                if (startChild != null) {
                    startChild.complete();
                }
            }
            startedFuture.complete(null);
        }

        if (!started && wasStarted) {
            startedFuture = new CompletableFuture<>();
        }
    }

    /**
     * Marks the server as ready or not ready, completing or resetting the ready future
     * and notifying status change listeners.
     */
    public final void setReady(boolean ready) {
        boolean wasReady = this.isReady;
        this.isReady = ready;

        if (ready && !wasReady && readyFuture != null && !readyFuture.isDone()) {
            readyFuture.complete(null);
        }

        if (!ready && wasReady) {
            readyFuture = new CompletableFuture<>();
        }

        if (wasReady != ready && !statusChangeListeners.isEmpty()) {
            for (StatusChangeListener listener : statusChangeListeners) {
                try {
                    listener.onStatusChanged(this.status, this.status);
                } catch (Exception e) {
                    LOG.warnf(e, "Error in status change listener for %s", config.getServerId());
                }
            }
        }
    }

    /**
     * Destroy the server process gracefully, then forcibly if needed.
     *
     * @param gracefulTimeoutMs time to wait for graceful shutdown before forcing (0 = force immediately)
     * @param forceTimeoutMs    time to wait after forcible kill
     * @return true if the process was terminated
     */
    protected boolean destroyProcess(long gracefulTimeoutMs, long forceTimeoutMs) {
        Process process = this.serverProcess;
        if (process == null || !process.isAlive()) {
            return true;
        }
        try {
            if (gracefulTimeoutMs > 0) {
                LOG.infof("Destroying server process (PID: %d)", process.pid());
                process.destroy();
                if (process.waitFor(gracefulTimeoutMs, TimeUnit.MILLISECONDS)) {
                    return true;
                }
                LOG.warnf("Server process did not terminate gracefully, forcing kill");
            }
            process.destroyForcibly();
            if (process.waitFor(forceTimeoutMs, TimeUnit.MILLISECONDS)) {
                return true;
            }
            LOG.errorf("Server process did not terminate after forceful kill (PID: %d) - may be zombie", process.pid());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Returns the workspace this server is associated with.
     */
    public Workspace getWorkspace() {
        return workspace;
    }

    /**
     * Check if server can start and prepare for starting.
     * Returns true if can proceed, false if should skip (already running).
     * Common logic for both LSP and DAP servers.
     */
    protected boolean prepareStart() {
        // Don't restart if already running or starting
        if (getStatus() == ServerStatus.RUNNING || getStatus() == ServerStatus.INDEXING || getStatus() == ServerStatus.STARTING) {
            LOG.warnf("Server already running/starting (status: %s), ignoring start call", getStatus());
            return false;
        }

        // If restarting, kill old process first
        Process process = getServerProcess();
        if (process != null && process.isAlive()) {
            LOG.infof("Killing old server process before restart (PID: %d)", process.pid());
            process.destroyForcibly();
        }

        // Set status to STARTING
        setStatus(ServerStatus.STARTING);
        return true;
    }

    /**
     * Log error with full stack trace to trace collector.
     */
    protected void logErrorToTrace(Exception e) {
        LOG.errorf(e, "Failed to start %s", config.getServerId());

        StringBuilder stackTrace = new StringBuilder();
        stackTrace.append("[Error starting ").append(config.getName()).append("]\n");
        Throwable current = e;
        while (current != null) {
            stackTrace.append(current.getClass().getName()).append(": ").append(current.getMessage()).append("\n");
            for (StackTraceElement element : current.getStackTrace()) {
                stackTrace.append("  at ").append(element.toString()).append("\n");
            }
            current = current.getCause();
            if (current != null) {
                stackTrace.append("Caused by: ");
            }
        }

        try {
            addTrace(stackTrace.toString(), TraceCollector.MessageType.ERROR);
        } catch (Exception traceEx) {
            LOG.errorf(traceEx, "Failed to add trace for error!");
        }
    }

    /**
     * Add error handler to a CompletableFuture that logs to trace collector.
     */
    protected <T> CompletableFuture<T> withErrorLogging(CompletableFuture<T> future) {
        return future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                if (getStatus() == ServerStatus.STARTING || getStatus() == ServerStatus.INSTALLING) {
                    setStatus(ServerStatus.START_FAILED);
                }
                Exception e = throwable instanceof Exception ? (Exception) throwable : new Exception(throwable);
                logErrorToTrace(e);
            }
        });
    }


    @Override
    protected void onRouteRequestStart(String method, Object params) {
        if (isRouteRequestTracedByWire()) {
            return;
        }
        ServerTrace trace = getServerTrace();
        if (trace != ServerTrace.off) {
            boolean verbose = trace == ServerTrace.verbose;
            getTracing().traceRequest(method, params, verbose);
        }
    }

    @Override
    protected void onRouteRequestEnd(String method, Object params, Object result, Throwable error, long durationMs) {
        if (isRouteRequestTracedByWire()) {
            return;
        }
        ServerTrace trace = getServerTrace();
        if (trace != ServerTrace.off) {
            boolean verbose = trace == ServerTrace.verbose;
            getTracing().traceResponse(method, result, error, durationMs, verbose);
        }
    }

    /**
     * Returns true if bind requests are already traced at the wire level
     * (e.g., by TracingMessageConsumer via wrapMessages on the LSP connection).
     * In that case, onRouteRequestStart/End should not add duplicate traces.
     * Subclasses with wire-level tracing (LspServer) override to return true.
     */
    protected boolean isRouteRequestTracedByWire() {
        return false;
    }

    /**
     * Returns the trace collector used for application-level tracing.
     */
    public TraceCollector getTraceCollector() {
        return traceCollector;
    }

    /**
     * Returns the wire-level tracing consumer for LSP/DAP message logging.
     */
    public TracingMessageConsumer getTracing() {
        return tracing;
    }

    /**
     * Returns the configured trace level for this server (off, messages, or verbose).
     */
    public abstract ServerTrace getServerTrace();

    public abstract ServerType getServerType();

    /**
     * Creates and returns the trace collector for this server type.
     * Called once during construction.
     */
    protected abstract TraceCollector initializeTraceCollector(Workspace workspace);
}
