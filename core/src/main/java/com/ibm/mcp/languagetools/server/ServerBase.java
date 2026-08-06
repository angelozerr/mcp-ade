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

import com.ibm.mcp.languagetools.client.BindEndpointSupport;
import com.ibm.mcp.languagetools.dap.session.DapSession;
import com.ibm.mcp.languagetools.operation.OperationEntry;
import com.ibm.mcp.languagetools.configuration.ServerTrace;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.trace.TracingMessageConsumer;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Base class for server implementations (LSP and DAP).
 * Provides common functionality for managing server configuration and lifecycle,
 * as well as bindRequest/bindNotification support via BindEndpointSupport.
 *
 * @param <T> The type of server configuration (LspServerConfig or DapServerConfig)
 */
public abstract class ServerBase<T extends ServerConfigBase> extends BindEndpointSupport {

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
    private final String contextId;
    protected ExecutorService executorService; // Not final - can be recreated after error
    private final TraceCollector traceCollector;
    private final TracingMessageConsumer tracing;
    private volatile ServerStatus status = ServerStatus.NOT_STARTED;
    private volatile String statusMessage = null;
    private volatile String errorMessage = null;
    private final List<StatusChangeListener> statusChangeListeners = new CopyOnWriteArrayList<>();

    private Process serverProcess;
    private volatile boolean isReady;
    private volatile boolean isStarted;
    private CompletableFuture<Void> readyFuture;
    private CompletableFuture<Void> startedFuture;
    private volatile OperationEntry operationEntry;

    public ServerBase(T config, Workspace workspace) {
        this(config, workspace, config.getServerId());
    }

    /**
     * Constructor with explicit context ID for tracing.
     * <p>
     * The contextId identifies the trace source:
     * <ul>
     *   <li>LSP: the server ID (e.g. "jdtls")</li>
     *   <li>DAP: "serverId#sessionId" (e.g. "js-debug#session-123")</li>
     * </ul>
     */
    protected ServerBase(T config, Workspace workspace, String contextId) {
        super(config, workspace);
        this.config = config;
        this.workspace = workspace;
        this.contextId = contextId;
        this.executorService = Executors.newCachedThreadPool();
        this.readyFuture = new CompletableFuture<>();
        this.startedFuture = new CompletableFuture<>();

        var workspaceRoot = workspace.getNormalizedUri();
        this.traceCollector = initializeTraceCollector(workspace);
        this.tracing = new TracingMessageConsumer(traceCollector, workspaceRoot, contextId);
    }

    public final String getContextId() {
        return contextId;
    }

    protected Process getServerProcess() {
        return serverProcess;
    }

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

        if (config.getWorkingDirectory() != null) {
            String resolvedWorkingDir = ServerVariables.resolve(config.getWorkingDirectory(), config);
            pb.directory(Paths.get(resolvedWorkingDir).toFile());
            addTrace(String.format("Working directory: %s", resolvedWorkingDir));
        }

        this.serverProcess = pb.start();
        addTrace(String.format("Server process started (PID: %d)", serverProcess.pid()));
        return this.serverProcess;
    }

    protected List<String> buildCommand() throws IOException {
        String cmd = getConfig().getCommand();
        if (cmd == null) {
            throw new IOException("No command configured for current OS");
        }
        return parseCommandLine(cmd);
    }

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
        traceCollector.addTrace(workspace.getNormalizedUri(), contextId, content);
    }

    /**
     * Add a trace message with a specific message type.
     */
    protected void addTrace(String content, TraceCollector.MessageType messageType) {
        if (!traceCollector.isEnabled()) {
            return;
        }
        traceCollector.addTrace(workspace.getNormalizedUri(), contextId, content, messageType);
    }

    /**
     * Get the server configuration.
     */
    public T getConfig() {
        return config;
    }

    public Path getServerHome() {
        return config.getServerHome();
    }
    /**
     * Get the current server status.
     */
    public final ServerStatus getStatus() {
        return status;
    }

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

    public final String getStatusMessage() {
        return statusMessage;
    }

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
        executorService.submit(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(getServerProcess().getErrorStream()))) {
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
            } catch (java.io.IOException e) {
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

    public void setOperationEntry(OperationEntry operationEntry) {
        this.operationEntry = operationEntry;
    }

    public OperationEntry getOperationEntry() {
        return operationEntry;
    }

    public CompletableFuture<Void> waitForStarted() {
        if (isStarted) {
            return CompletableFuture.completedFuture(null);
        }
        return startedFuture;
    }

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
     * @deprecated Use {@link #waitForReady()} instead
     */
    @Deprecated
    public CompletableFuture<Void> waitUntilReady() {
        if (isReady) {
            return CompletableFuture.completedFuture(null);
        }
        return readyFuture;
    }

    /**
     * @deprecated Use {@link #waitForReady()} instead
     */
    @Deprecated
    public CompletableFuture<Void> waitUntilReady(long timeoutMs) {
        if (isReady) {
            return CompletableFuture.completedFuture(null);
        }
        return readyFuture.orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public final boolean isStarted() {
        return isStarted;
    }

    public final boolean isReady() {
        return isReady;
    }

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
                if (process.waitFor(gracefulTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return true;
                }
                LOG.warnf("Server process did not terminate gracefully, forcing kill");
            }
            process.destroyForcibly();
            if (process.waitFor(forceTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return true;
            }
            LOG.errorf("Server process did not terminate after forceful kill (PID: %d) - may be zombie", process.pid());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Workspace getWorkspace() {
        return workspace;
    }

    /**
     * Check if server can start and prepare for starting.
     * Returns true if can proceed, false if should skip (already running).
     * Common logic for both LSP and DAP servers.
     */
    protected boolean checkAndPrepareStart() {
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
                Exception e = throwable instanceof Exception ? (Exception) throwable : new Exception(throwable);
                logErrorToTrace(e);
            }
        });
    }


    @Override
    protected void onBindRequestStart(String method, Object params) {
        if (isBindRequestTracedByWire()) {
            return;
        }
        ServerTrace trace = getServerTrace();
        if (trace != ServerTrace.off) {
            boolean verbose = trace == ServerTrace.verbose;
            getTracing().traceRequest(method, params, verbose);
        }
    }

    @Override
    protected void onBindRequestEnd(String method, Object params, Object result, Throwable error, long durationMs) {
        if (isBindRequestTracedByWire()) {
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
     * In that case, onBindRequestStart/End should not add duplicate traces.
     * Subclasses with wire-level tracing (LspServer) override to return true.
     */
    protected boolean isBindRequestTracedByWire() {
        return false;
    }

    public TraceCollector getTraceCollector() {
        return traceCollector;
    }

    public TracingMessageConsumer getTracing() {
        return tracing;
    }

    public abstract ServerTrace getServerTrace();

    protected abstract TraceCollector initializeTraceCollector(Workspace workspace);
}
