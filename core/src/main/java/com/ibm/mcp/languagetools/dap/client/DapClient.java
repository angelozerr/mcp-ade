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
package com.ibm.mcp.languagetools.dap.client;

import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.eclipse.lsp4j.debug.services.IDebugProtocolServer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * DAP client implementation that receives events from the debug adapter.
 * Routes events to a registered DapEventListener (typically a DapSession).
 *
 * Supports parent-child relationships for nested debug sessions (e.g., when debugging
 * spawned processes in Node.js).
 */
public class DapClient implements IDebugProtocolClient {

    private static final Logger LOG = Logger.getLogger(DapClient.class);

    private DapEventListener eventListener;

    // Initialization state tracking (like lsp4ij)
    // Package-private so DapServer can access if needed (though now it's in DapClient.connectAndInitialize)
    final CompletableFuture<Capabilities> capabilitiesFuture = new CompletableFuture<>();
    final CompletableFuture<Void> initializedEventFuture = new CompletableFuture<>();

    // Parent-child session support
    private DapClient parentClient;
    private final List<DapClient> childrenClients = new CopyOnWriteArrayList<>();
    private Process runningProcess; // Process launched via runInTerminal

    // Factory for creating child debug sessions
    private Function<Map<String, Object>, CompletableFuture<DapClient>> childSessionFactory;
    private ExecutorService executorService;

    public DapClient() {
    }

    /**
     * Create a child client with a parent reference.
     */
    public DapClient(DapClient parentClient) {
        this.parentClient = parentClient;
    }

    public void setEventListener(DapEventListener listener) {
        this.eventListener = listener;
    }

    /**
     * Set the factory for creating child debug sessions.
     * This is called when the debug adapter requests to start a new debugging session
     * (e.g., for debugging spawned processes).
     *
     * @param factory function that takes DAP parameters and returns a CompletableFuture of DapClient
     */
    public void setChildSessionFactory(Function<Map<String, Object>, CompletableFuture<DapClient>> factory) {
        this.childSessionFactory = factory;
    }

    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    public DapClient getParentClient() {
        return parentClient;
    }

    public List<DapClient> getChildrenClients() {
        return new ArrayList<>(childrenClients);
    }

    // ========== Event Notifications from Debug Adapter ==========

    @Override
    public void initialized() {
        LOG.info("Initialized event received");
        // Complete the future (like lsp4ij)
        initializedEventFuture.complete(null);

        if (eventListener != null) {
            eventListener.onInitialized();
        }
    }

    /**
     * Get the capabilities from initialize response.
     */
    public Capabilities getCapabilities() {
        return capabilitiesFuture.getNow(null);
    }

    @Override
    public void stopped(StoppedEventArguments args) {
        LOG.debugf("Stopped event: reason=%s, threadId=%d", args.getReason(), args.getThreadId());
        if (eventListener != null) {
            eventListener.onStopped(args);
        }
    }

    @Override
    public void continued(ContinuedEventArguments args) {
        LOG.debugf("Continued event: threadId=%d", args.getThreadId());
        if (eventListener != null) {
            eventListener.onContinued(args);
        }
    }

    @Override
    public void exited(ExitedEventArguments args) {
        LOG.infof("Exited event: exitCode=%d", args.getExitCode());

        if (!initializedEventFuture.isDone()) {
            LOG.warnf("Program exited before 'initialized' event - completing future to prevent hang");
            initializedEventFuture.complete(null);
        }

        if (eventListener != null) {
            eventListener.onExited(args);
        }
    }

    @Override
    public void terminated(TerminatedEventArguments args) {
        LOG.info("Terminated event");

        // CRITICAL FIX: If program terminates before 'initialized' event arrives,
        // complete initializedEventFuture to unblock connectAndInitialize()
        // This happens when the debugged program crashes immediately after launch
        if (!initializedEventFuture.isDone()) {
            LOG.warnf("Program terminated before 'initialized' event - completing future to prevent hang");
            initializedEventFuture.complete(null);
        }

        if (eventListener != null) {
            eventListener.onTerminated(args);
        }
    }

    @Override
    public void thread(ThreadEventArguments args) {
        LOG.debugf("Thread event: reason=%s, threadId=%d", args.getReason(), args.getThreadId());
        if (eventListener != null) {
            eventListener.onThread(args);
        }
    }

    @Override
    public void output(OutputEventArguments args) {
        LOG.debugf("Output event: category=%s, output=%s", args.getCategory(), args.getOutput());
        if (eventListener != null) {
            eventListener.onOutput(args);
        }
    }

    @Override
    public void breakpoint(BreakpointEventArguments args) {
        LOG.debugf("Breakpoint event: reason=%s", args.getReason());
        if (eventListener != null) {
            eventListener.onBreakpoint(args);
        }
    }

    @Override
    public void module(ModuleEventArguments args) {
        LOG.debugf("Module event: reason=%s", args.getReason());
        if (eventListener != null) {
            eventListener.onModule(args);
        }
    }

    @Override
    public void loadedSource(LoadedSourceEventArguments args) {
        LOG.debugf("LoadedSource event: reason=%s", args.getReason());
        if (eventListener != null) {
            eventListener.onLoadedSource(args);
        }
    }

    @Override
    public void process(ProcessEventArguments args) {
        LOG.debugf("Process event: name=%s", args.getName());
        if (eventListener != null) {
            eventListener.onProcess(args);
        }
    }

    @Override
    public void capabilities(CapabilitiesEventArguments args) {
        LOG.debug("Capabilities event");
        if (eventListener != null) {
            eventListener.onCapabilities(args);
        }
    }

    @Override
    public void progressStart(ProgressStartEventArguments args) {
        LOG.debugf("ProgressStart event: progressId=%s, title=%s", args.getProgressId(), args.getTitle());
        if (eventListener != null) {
            eventListener.onProgressStart(args);
        }
    }

    @Override
    public void progressUpdate(ProgressUpdateEventArguments args) {
        LOG.debugf("ProgressUpdate event: progressId=%s", args.getProgressId());
        if (eventListener != null) {
            eventListener.onProgressUpdate(args);
        }
    }

    @Override
    public void progressEnd(ProgressEndEventArguments args) {
        LOG.debugf("ProgressEnd event: progressId=%s", args.getProgressId());
        if (eventListener != null) {
            eventListener.onProgressEnd(args);
        }
    }

    @Override
    public void invalidated(InvalidatedEventArguments args) {
        LOG.debugf("Invalidated event: areas=%s", (Object) args.getAreas());
        if (eventListener != null) {
            eventListener.onInvalidated(args);
        }
    }

    @Override
    public void memory(MemoryEventArguments args) {
        LOG.debugf("Memory event: memoryReference=%s", args.getMemoryReference());
        if (eventListener != null) {
            eventListener.onMemory(args);
        }
    }

    // ========== Reverse Requests (from server to client) ==========

    /**
     * Called when the debug adapter wants to start a new debug session.
     * This is used for debugging child processes (e.g., Node.js spawns).
     *
     * @param args the start debugging request arguments containing the configuration
     * @return a CompletableFuture that completes when the child session is started
     */
    @Override
    public CompletableFuture<Void> startDebugging(StartDebuggingRequestArguments args) {
        LOG.infof("StartDebugging request received: %s", args.getRequest());

        if (childSessionFactory == null) {
            LOG.warnf("No child session factory configured, cannot start child debug session");
            return CompletableFuture.failedFuture(
                new IllegalStateException("Child session factory not configured")
            );
        }

        // Extract configuration from the request
        Map<String, Object> configuration = new HashMap<>();
        if (args.getConfiguration() != null && args.getConfiguration() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) args.getConfiguration();
            configuration.putAll(config);
        }

        // NOTE: Don't add "request" field - vscode-js-debug child sessions don't need it
        // The server already knows this is a launch for the pending target
        // If we add "request": 0 (int enum), it causes the launch to fail

        LOG.debugf("Starting child debug session with config: %s", configuration);

        // Create the child session using the factory
        return childSessionFactory.apply(configuration)
            .thenAccept(childClient -> {
                // Track the child client
                childClient.parentClient = this;
                childrenClients.add(childClient);
                LOG.infof("Child debug session started successfully, total children: %d", childrenClients.size());
            })
            .exceptionally(ex -> {
                LOG.errorf(ex, "Failed to start child debug session");
                return null;
            });
    }

    /**
     * Called when the debug adapter wants to run a command in a terminal.
     *
     * @param args the run in terminal request arguments
     * @return a CompletableFuture with the process ID if successful
     */
    @Override
    public CompletableFuture<RunInTerminalResponse> runInTerminal(RunInTerminalRequestArguments args) {
        LOG.infof("RunInTerminal request: kind=%s, title=%s, cwd=%s", args.getKind(), args.getTitle(), args.getCwd());

        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(java.util.Arrays.asList(args.getArgs()));

                // Set working directory if provided
                if (args.getCwd() != null && !args.getCwd().isEmpty()) {
                    pb.directory(new java.io.File(args.getCwd()));
                }

                // Set environment variables if provided
                if (args.getEnv() != null) {
                    pb.environment().putAll(args.getEnv());
                }

                LOG.infof("Launching process: %s", String.join(" ", args.getArgs()));
                Process process = pb.start();

                // Get process ID
                long pid = process.pid();
                LOG.infof("Process launched with PID: %d", pid);

                // Store process for cleanup
                runningProcess = process;

                // Capture stdout and send to DAP client
                runOnExecutor(() -> {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (eventListener != null) {
                                OutputEventArguments output = new OutputEventArguments();
                                output.setOutput(line + "\n");
                                output.setCategory("stdout");
                                eventListener.onOutput(output);
                            }
                        }
                    } catch (Exception e) {
                        LOG.errorf(e, "Error reading stdout: %s", e.getMessage());
                    }
                });

                // Capture stderr and send to DAP client
                runOnExecutor(() -> {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(process.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (eventListener != null) {
                                eventListener.onOutput(createErrorOutput(line + "\n"));
                            }
                        }
                    } catch (Exception e) {
                        LOG.errorf(e, "Error reading stderr: %s", e.getMessage());
                    }
                });

                // Monitor process termination in background
                runOnExecutor(() -> {
                    try {
                        int exitCode = process.waitFor();
                        LOG.infof("Process %d exited with code: %d", pid, exitCode);

                        // Send exit notification
                        if (eventListener != null) {
                            OutputEventArguments output = new OutputEventArguments();
                            output.setOutput(String.format("\nProcess exited with code: %d\n", exitCode));
                            output.setCategory("console");
                            eventListener.onOutput(output);
                        }
                    } catch (InterruptedException e) {
                        LOG.warnf("Process monitoring interrupted");
                        java.lang.Thread.currentThread().interrupt();
                    }
                });

                RunInTerminalResponse response = new RunInTerminalResponse();
                if (pid <= Integer.MAX_VALUE) {
                    response.setProcessId((int) pid);
                }
                return response;

            } catch (Exception e) {
                LOG.errorf(e, "Failed to launch process: %s", e.getMessage());

                // Send error output event
                if (eventListener != null) {
                    eventListener.onOutput(createErrorOutput(
                            String.format("Failed to launch process: %s\n", e.getMessage())));
                }

                throw new RuntimeException("Failed to launch process", e);
            }
        });
    }

    /**
     * Terminate all child debug sessions.
     */
    public CompletableFuture<Void> terminateChildSessions() {
        // Kill any process launched via runInTerminal
        if (runningProcess != null && runningProcess.isAlive()) {
            LOG.infof("Destroying runInTerminal process (PID: %d)", runningProcess.pid());
            runningProcess.destroyForcibly();
            runningProcess = null;
        }

        if (childrenClients.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        LOG.infof("Terminating %d child debug sessions", childrenClients.size());

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (DapClient child : childrenClients) {
            // Recursively terminate children's children
            futures.add(child.terminateChildSessions());
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                childrenClients.clear();
                LOG.infof("All child debug sessions terminated");
            });
    }

    // ========== Initialization (like lsp4ij DAPClient.connectToServer + initialize) ==========

    /**
     * Connect to the DAP server and initialize the debug session.
     * This follows the exact lsp4ij sequence:
     * 1. initialize request → response (capabilities)
     * 2. launch/attach request → response
     * 3. Wait for initialized event
     * 4. setBreakpoints (if isDebug) ← IMPORTANT: before configurationDone
     * 5. configurationDone request
     *
     * @param debugServer the debug protocol server proxy
     * @param dapParameters the DAP parameters (launch/attach configuration)
     * @param isDebug true if debugging (will set breakpoints), false for run without debugging
     * @param serverId the DAP server ID
     * @param breakpointSender optional callback to send breakpoints (if isDebug=true)
     * @return CompletableFuture that completes when initialization is done
     */
    public CompletableFuture<Void> connectAndInitialize(
            IDebugProtocolServer debugServer,
            Map<String, Object> dapParameters,
            boolean isDebug,
            String serverId,
            java.util.function.Supplier<CompletableFuture<Void>> breakpointSender) {

        LOG.infof("Starting DAP connectAndInitialize sequence");

        // Prepare InitializeRequestArguments (like lsp4ij)
        InitializeRequestArguments initArgs = new InitializeRequestArguments();
        initArgs.setClientID("mcp-languagetools");
        initArgs.setClientName("MCP Language Tools");

        // Like lsp4ij: use "type" from dapParameters if present, else use serverId
        String adapterId = serverId;
        if (dapParameters.get("type") instanceof String type) {
            adapterId = type;
        }
        LOG.infof("Using adapterId: %s (from type in dapParameters or serverId)", adapterId);
        initArgs.setAdapterID(adapterId);

        initArgs.setPathFormat("path");
        initArgs.setLinesStartAt1(true);
        initArgs.setColumnsStartAt1(true);
        initArgs.setSupportsRunInTerminalRequest(true);
        initArgs.setSupportsStartDebuggingRequest(true);
        initArgs.setSupportsVariableType(true);
        initArgs.setSupportsVariablePaging(false);

        // Detect request type (launch vs attach)
        String requestType = (String) dapParameters.get("request");
        boolean isAttach = "attach".equals(requestType);

        // Step 1: Send initialize, THEN launch or attach (like lsp4ij - both run in parallel with configurationDone)
        CompletableFuture<?> launchAttachFuture = debugServer.initialize(initArgs)
            .thenAccept(capabilities -> {
                LOG.infof("Received capabilities from initialize");
                if (capabilities == null) {
                    LOG.warnf("Debug adapter returned null capabilities, using default");
                    capabilities = new org.eclipse.lsp4j.debug.Capabilities();
                }
                capabilitiesFuture.complete(capabilities);
            })
            .thenCompose(unused -> {
                if (isAttach) {
                    LOG.infof("Sending attach request");
                    return debugServer.attach(dapParameters);
                } else {
                    LOG.infof("Sending launch request");
                    return debugServer.launch(dapParameters);
                }
            })
            .whenComplete((q, t) -> {
                if (t != null) {
                    LOG.errorf(t, "Error during initialize/%s", isAttach ? "attach" : "launch");
                    if (eventListener != null) {
                        eventListener.onOutput(createErrorOutput(
                            String.format("Error during initialize/%s: %s",
                                isAttach ? "attach" : "launch", t.getMessage())));
                    }
                    capabilitiesFuture.completeExceptionally(t);
                    initializedEventFuture.completeExceptionally(t);
                }
            });

        // Step 2: Wait for initialized event, then send breakpoints (if debug mode), then configurationDone
        // This follows lsp4ij pattern: initialized → setBreakpoints → configurationDone
        CompletableFuture<Void> configurationDoneFuture = CompletableFuture
            .allOf(initializedEventFuture, capabilitiesFuture);

        // If debug mode, send breakpoints BEFORE configurationDone (like lsp4ij)
        if (isDebug && breakpointSender != null) {
            configurationDoneFuture = configurationDoneFuture
                .thenCompose(v -> {
                    LOG.infof("Received initialized event, sending breakpoints");
                    return breakpointSender.get();
                })
                .exceptionally(t -> {
                    LOG.errorf(t, "Failed to send breakpoints (non-fatal, continuing)");
                    return null;
                });
        }

        // Then send configurationDone (like lsp4ij - after breakpoints if debug mode)
        configurationDoneFuture = configurationDoneFuture
            .thenCompose(v -> {
                LOG.infof("Sending configurationDone");
                org.eclipse.lsp4j.debug.Capabilities caps = getCapabilities();

                // Only send configurationDone if supported
                if (caps != null && Boolean.TRUE.equals(caps.getSupportsConfigurationDoneRequest())) {
                    return debugServer.configurationDone(
                        new org.eclipse.lsp4j.debug.ConfigurationDoneArguments())
                        .exceptionally(t -> {
                            // Non-fatal: server may have already terminated
                            LOG.warnf(t, "configurationDone failed (non-fatal, server may be terminated)");
                            return null;
                        });
                }
                LOG.infof("Server doesn't support configurationDone, skipping");
                return CompletableFuture.completedFuture(null);
            });

        // Wait for BOTH futures to complete (like lsp4ij)
        return CompletableFuture.allOf(launchAttachFuture, configurationDoneFuture)
            .thenCompose(v -> {
                LOG.infof("DAP session initialized, requesting threads");
                // After configurationDone, request threads (like lsp4ij)
                return debugServer.threads()
                    .thenAccept(threadsResponse -> {
                        LOG.infof("Received threads response: %d threads",
                            threadsResponse.getThreads() != null ? threadsResponse.getThreads().length : 0);
                    })
                    .exceptionally(t -> {
                        LOG.warnf(t, "Failed to get threads (non-fatal)");
                        return null;
                    });
            })
            .thenRun(() -> {
                LOG.infof("DAP session fully initialized and ready");
            })
            .whenComplete((result, t) -> {
                if (t != null) {
                    LOG.errorf(t, "Error during DAP initialization sequence");
                    // Send error to UI
                    if (eventListener != null) {
                        eventListener.onOutput(createErrorOutput(
                            "DAP initialization failed: " + t.getMessage()));
                    }
                }
            });
    }

    private void runOnExecutor(Runnable task) {
        if (executorService != null) {
            executorService.submit(task);
        } else {
            CompletableFuture.runAsync(task);
        }
    }

    private OutputEventArguments createErrorOutput(String message) {
        var output = new OutputEventArguments();
        output.setCategory("stderr");
        output.setOutput("❌ " + message + "\n");
        return output;
    }
}
