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
package org.eclipse.mcp.ade.dap.tools;

import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.eclipse.mcp.ade.dap.server.DapConfigurationTemplate;
import org.eclipse.mcp.ade.dap.server.DapServerResolver;
import org.eclipse.mcp.ade.dap.session.DapSession;
import org.eclipse.mcp.ade.operation.OperationActor;
import org.eclipse.mcp.ade.dap.session.DapSessionManager;
import org.eclipse.mcp.ade.operation.OperationContext;
import org.eclipse.mcp.ade.operation.OperationEntry;
import org.eclipse.mcp.ade.operation.OperationTracker;
import org.eclipse.mcp.ade.progress.ProgressContext;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.eclipse.mcp.ade.progress.ProgressMonitorManager;
import org.eclipse.mcp.ade.progress.ProgressStep;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import org.eclipse.mcp.ade.tools.ToolException;
import org.eclipse.mcp.ade.utils.MapUtils;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.lsp4j.debug.*;
import org.eclipse.lsp4j.debug.Thread;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;


import static org.eclipse.mcp.ade.tools.CancellationSupport.executeWithCancellation;

/**
 * MCP Tools for Debug Adapter Protocol (DAP) operations.
 * <p>
 * Provides 20+ tools to control debug sessions across multiple languages:
 * - Session management (create, list, close)
 * - Breakpoints (set, remove, list)
 * - Execution control (launch, attach, continue, step)
 * - Inspection (stack trace, variables, evaluate)
 */
@Singleton
public class DapDebugTools {

    @Inject
    DapSessionManager sessionManager;

    @Inject
    Application application;

    @Inject
    DapServerResolver serverResolver;

    @Inject
    ProgressMonitorManager progressMonitorManager;

    @Inject
    OperationTracker operationTracker;

    private <T> T tracked(Map<String, Object> args, Supplier<T> action) {
        String workspaceUri = (String) args.get("cwd");
        String sessionId = (String) args.get("sessionId");
        DapSession session = null;
        if (sessionId != null) {
            try {
                session = sessionManager.getSession(sessionId);
                if (workspaceUri == null) {
                    workspaceUri = session.getWorkspace().getNormalizedUri();
                }
            } catch (Exception ignored) {
            }
        }
        OperationContext operationContext = operationTracker.startOperation(
                OperationTracker.resolveToolName("dap"), "tool", workspaceUri);
        operationContext.setArguments(args);
        if (session != null) {
            operationContext.setSessionId(sessionId);
            operationContext.setSessionName(session.getSessionName());
        }
        try {
            T result = action.get();
            operationContext.setResult(String.valueOf(result));
            operationContext.complete();
            return result;
        } catch (ToolException e) {
            operationContext.fail(e.getMessage());
            throw e;
        } catch (Exception e) {
            operationContext.fail(e.getMessage());
            throw new ToolException(e.getMessage(), e);
        }
    }

    private static Map<String, Object> buildArgs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }

    // ========== Session Management ==========


    @Tool(
            name = "list_debug_adapters",
            description = "List available debug adapters with their IDs and supported languages. " +
                    "Optionally filter by file URI to get adapters suitable for that file. " +
                    "Without cwd: returns available debug adapter configurations. " +
                    "With cwd: returns debug adapter configurations enriched with installation status " +
                    "and error details to help diagnose adapter issues. " +
                    "Use the adapter ID with start_debugging.")
    public List<Map<String, Object>> listDebugAdapters(
            @ToolArg(description = ToolArgDescriptions.CWD, required = false) String cwd,
            @ToolArg(description = "Optional file URI to filter adapters (e.g., 'file:///path/to/Main.java')", required = false) String uri) {
        return tracked(buildArgs("cwd", cwd, "uri", uri), () -> {
            List<Map<String, Object>> adapters;
            if (uri != null && !uri.isEmpty()) {
                Path basePath = (cwd != null && !cwd.isEmpty()) ? Paths.get(cwd) : null;
                adapters = sessionManager.listDebugAdaptersForFile(URI.create(uri), basePath);
            } else {
                adapters = sessionManager.listDebugAdapters();
            }

            for (Map<String, Object> adapter : adapters) {
                String id = (String) adapter.get("id");
                DapServerConfig config = application.getDapServerConfig(id);
                if (config != null) {
                    String extensionId = config.getExtensionId();
                    if (extensionId != null) {
                        adapter.put("extensionId", extensionId);
                        if (config.getExtensionName() != null) {
                            adapter.put("extensionName", config.getExtensionName());
                        }
                    }
                    adapter.put("enabled", serverResolver.isEnabled(config));

                    if (config.getRuntime() != null) {
                        adapter.put("runtime", config.getRuntime());
                        if (config.getRuntimeConfig() != null) {
                            config.getRuntimeConfig().addRuntimeInfo(adapter);
                        }
                    }

                    if (cwd != null && !cwd.isEmpty()) {
                        config.addInstallationStatus(adapter);
                    }
                }
            }

            return adapters;
        });
    }

    @Tool(
            name = "list_debug_sessions",
            description = "List all active debug sessions with their state (CREATED, RUNNING, PAUSED, etc).")
    public List<Map<String, Object>> getListDebugSessions() {
        return tracked(buildArgs(), () -> sessionManager.listSessions());
    }

    @Tool(
            name = "close_debug_session",
            description = "Close and terminate a debug session, stopping the debugged program.")
    public Map<String, Object> closeDebugSessionSynch(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () ->
                closeDebugSession(sessionId).join());
    }

    public CompletableFuture<Map<String, Object>> closeDebugSession(String sessionId) {
        return sessionManager.closeSession(sessionId);
    }

    // ========== Breakpoints ==========

    @Tool(description = "Set a breakpoint at a specific file and line number. " +
            "File path should be absolute or relative to workspace root. " +
            "Optionally add a condition (e.g., 'x > 10') to break only when true.")
    public Map<String, Object> set_breakpoint(
            String sessionId,
            String file,
            int line,
            String condition) {
        return tracked(buildArgs("sessionId", sessionId, "file", file, "line", line, "condition", condition), () -> {
            DapSession session = sessionManager.getSession(sessionId);
            DapSession.BreakpointInfo info = session.setBreakpoint(file, line, condition).join();

            return Map.of(
                    "success", true,
                    "breakpointId", info.breakpointId,
                    "file", info.file,
                    "line", info.line,
                    "verified", info.verified,
                    "message", "Breakpoint set at " + file + ":" + line
            );
        });
    }

    @Tool(description = "Remove a previously set breakpoint by its ID.")
    public Map<String, Object> remove_breakpoint(
            String sessionId,
            String breakpointId) {
        return tracked(buildArgs("sessionId", sessionId, "breakpointId", breakpointId), () -> {
            DapSession session = sessionManager.getSession(sessionId);
            boolean removed = session.removeBreakpoint(breakpointId).join();

            return Map.of(
                    "success", removed,
                    "breakpointId", breakpointId,
                    "message", removed ? "Breakpoint removed" : "Breakpoint not found"
            );
        });
    }

    @Tool(description = "List all breakpoints currently set in a debug session.")
    public Map<String, Object> list_all_breakpoints(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () -> {
            DapSession session = sessionManager.getSession(sessionId);
            List<DapSession.BreakpointInfo> breakpoints = session.listBreakpoints();

            List<Map<String, Object>> bpList = breakpoints.stream()
                    .map(bp -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("breakpointId", bp.breakpointId);
                        map.put("file", bp.file);
                        map.put("line", bp.line);
                        map.put("verified", bp.verified);
                        map.put("condition", bp.condition != null ? bp.condition : "");
                        return map;
                    })
                    .toList();

            return Map.of(
                    "success", true,
                    "count", bpList.size(),
                    "breakpoints", bpList
            );
        });
    }

    // ========== Instruction Breakpoints ==========

    @Tool(
            name = "set_instruction_breakpoint",
            description = "Set a breakpoint at a memory instruction address. " +
                    "Use instructionPointerReference from get_stack_trace or address from disassemble output. " +
                    "Requires adapter support (supportsInstructionBreakpoints capability).")
    public Map<String, Object> setInstructionBreakpointSync(
            String sessionId,
            @ToolArg(description = "Instruction memory reference (e.g., from stack frame's instructionPointerReference)") String instructionReference,
            @ToolArg(description = "Optional byte offset from the instruction reference", required = false) Integer offset,
            @ToolArg(description = "Optional condition expression (e.g., 'x > 10')", required = false) String condition) {
        return tracked(buildArgs("sessionId", sessionId, "instructionReference", instructionReference,
                "offset", offset, "condition", condition), () ->
                setInstructionBreakpoint(sessionId, instructionReference, offset, condition).join());
    }

    public CompletableFuture<Map<String, Object>> setInstructionBreakpoint(
            String sessionId, String instructionReference, Integer offset, String condition) {
        DapSession session = sessionManager.getSession(sessionId);
        return session.setInstructionBreakpoint(instructionReference, offset, condition)
                .thenApply(info -> {
                    Map<String, Object> result = new HashMap<>();
                    result.put("success", true);
                    result.put("breakpointId", info.breakpointId);
                    result.put("instructionReference", info.instructionReference);
                    if (info.offset != null) {
                        result.put("offset", info.offset);
                    }
                    result.put("verified", info.verified);
                    result.put("message", "Instruction breakpoint set at " + instructionReference);
                    return result;
                });
    }

    @Tool(
            name = "remove_instruction_breakpoint",
            description = "Remove a previously set instruction breakpoint by its ID.")
    public Map<String, Object> removeInstructionBreakpointSync(
            String sessionId,
            String breakpointId) {
        return tracked(buildArgs("sessionId", sessionId, "breakpointId", breakpointId), () ->
                removeInstructionBreakpoint(sessionId, breakpointId).join());
    }

    public CompletableFuture<Map<String, Object>> removeInstructionBreakpoint(
            String sessionId, String breakpointId) {
        DapSession session = sessionManager.getSession(sessionId);
        return session.removeInstructionBreakpoint(breakpointId)
                .thenApply(removed -> Map.of(
                        "success", removed,
                        "breakpointId", breakpointId,
                        "message", removed ? "Instruction breakpoint removed" : "Instruction breakpoint not found"
                ));
    }

    @Tool(
            name = "list_instruction_breakpoints",
            description = "List all instruction breakpoints currently set in a debug session.")
    public Map<String, Object> listInstructionBreakpointsSync(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () ->
                listInstructionBreakpoints(sessionId));
    }

    public Map<String, Object> listInstructionBreakpoints(String sessionId) {
        DapSession session = sessionManager.getSession(sessionId);
        List<DapSession.InstructionBreakpointInfo> breakpoints = session.listInstructionBreakpoints();

        List<Map<String, Object>> bpList = breakpoints.stream()
                .map(bp -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("breakpointId", bp.breakpointId);
                    map.put("instructionReference", bp.instructionReference);
                    if (bp.offset != null) {
                        map.put("offset", bp.offset);
                    }
                    map.put("verified", bp.verified);
                    map.put("condition", bp.condition != null ? bp.condition : "");
                    return map;
                })
                .toList();

        return Map.of(
                "success", true,
                "count", bpList.size(),
                "instructionBreakpoints", bpList
        );
    }

    // ========== Debugging Lifecycle ==========

    @Tool(
            name = "start_debugging",
            description = "Start debugging (launch or attach) based on configuration.request. " +
                    "Creates a debug session automatically and starts the program. " +
                    "Returns sessionId to use in other debug operations. " +
                    "Use get_debug_templates() to see available configuration parameters. " +
                    "Set debugMode=false to run without debugging (no breakpoints). " +
                    "Optionally specify breakpoints to set before launching (avoids race conditions). " +
                    "After starting, use get_console_output(sessionId) to see program output (stdout/stderr/console.log).",
            structuredContent = true
    )
    public Map<String, Object> startDebuggingSync(
            @ToolArg(description = "ID of the debug adapter (e.g., 'java-debug', 'vscode-js-debug', 'debugpy')") String debuggerId,
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Debug configuration with 'request' field ('launch' or 'attach')") Map<String, Object> configuration,
            @ToolArg(description = "Optional breakpoints to set before starting [{file, line, condition?}]", required = false) List<Map<String, Object>> breakpoints,
            @ToolArg(description = "Optional session name (auto-generated if not provided)", required = false) String sessionName,
            @ToolArg(description = "Debug mode: true=debug with breakpoints, false=run without debugging (default)", required = false) Boolean debugMode,
            Cancellation cancellation,
            Progress progress) {
        return startDebugging(debuggerId, cwd, configuration, breakpoints,
                sessionName, debugMode, cancellation, progress).join();
    }

    public CompletableFuture<Map<String, Object>> startDebugging(
            String debuggerId,
            String cwd,
            Map<String, Object> configuration,
            List<Map<String, Object>> breakpoints,
            String sessionName,
            Boolean debugMode,
            Cancellation cancellation,
            Progress progress) {

        OperationContext operationContext = operationTracker.startOperation(
                OperationTracker.resolveToolName("dap"), "tool", cwd);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("debuggerId", debuggerId);
        args.put("cwd", cwd);
        String request = MapUtils.getString(configuration, "request");
        if (request != null) {
            args.put("request", request);
        }
        if (sessionName != null) {
            args.put("sessionName", sessionName);
        }
        if (debugMode != null) {
            args.put("debugMode", debugMode);
        }
        operationContext.setArguments(args);
        OperationEntry serverEntry = operationContext.addEntry(debuggerId, debuggerId);

        // Create progress monitor (MCP + Admin WebSocket contributors)
        ProgressMonitor progressMonitor = progressMonitorManager.createProgressMonitor(
                progress, cancellation, ProgressContext.forOperation("start_debugging", "Start debugging"));

        // Check if the debug adapter exists and is enabled
        DapServerConfig dapConfig = serverResolver.getEnabledDapConfig(debuggerId);

        // Define steps for DAP operations (with separate runtime/server install steps)
        boolean hasRuntime = dapConfig.getRuntimeConfig() != null;
        String serverInstallStep = "Installing " + debuggerId;

        if (hasRuntime) {
            String runtimeInstallStep = "Installing " + dapConfig.getRuntimeConfig().getName();
            progressMonitor
                    .addStep(runtimeInstallStep, 0.15)
                    .addStep(serverInstallStep, 0.15)
                    .addStep(ProgressStep.STARTING, 0.20)
                    .addStep(ProgressStep.EXECUTING, 0.50);
            progressMonitor.beginStep(runtimeInstallStep);
        } else {
            progressMonitor
                    .addStep(serverInstallStep, 0.30)
                    .addStep(ProgressStep.STARTING, 0.20)
                    .addStep(ProgressStep.EXECUTING, 0.50);
            progressMonitor.beginStep(serverInstallStep);
        }

        // Convert cwd to URI (handle both file:// URIs and Windows/Unix paths)
        URI uri;
        if (cwd.startsWith("file:")) {
            uri = URI.create(cwd);
        } else {
            // Convert path to URI
            Path path = Paths.get(cwd);
            uri = path.toUri();
        }

        // Flush pending file watcher events so language servers see recent file changes
        var workspace = application.getWorkspaceForPath(cwd);
        if (workspace != null) {
            workspace.flushFileWatcher();
        }

        // Generate session name if not provided
        String actualSessionName = sessionName != null ? sessionName : "Debug Session";

        // Default debugMode to false if not provided (run without debugging)
        boolean actualDebugMode = debugMode != null ? debugMode : false;

        // Create session (created by AI agent via MCP)
        DapSession session = sessionManager.createSession(uri, debuggerId, actualSessionName, OperationActor.AGENT);
        String sessionId = session.getSessionId();

        // Set breakpoints before launching (if provided and in debug mode)
        CompletableFuture<Void> breakpointsFuture = CompletableFuture.completedFuture(null);
        if (actualDebugMode && breakpoints != null && !breakpoints.isEmpty()) {
            CompletableFuture<?>[] bpFutures = breakpoints.stream()
                    .filter(bp -> bp.get("file") != null && bp.get("line") != null)
                    .map(bp -> {
                        String file = MapUtils.getString(bp, "file");
                        int line = MapUtils.requireInteger(bp, "line");
                        String condition = MapUtils.getString(bp, "condition");
                        return session.setBreakpoint(file, line, condition);
                    })
                    .toArray(CompletableFuture[]::new);
            breakpointsFuture = CompletableFuture.allOf(bpFutures);
        }

        // Chain: breakpoints → launch/attach → add metadata
        // Note: progressMonitor.executeWithCancellation is already called inside trackFuture,
        // so we don't need to wrap again here (would create double wrapping)
        return breakpointsFuture.thenCompose(v -> {
            String requestType = MapUtils.getString(configuration, "request");

            if ("attach".equals(requestType)) {
                // Attach mode
                Integer processId = MapUtils.getInteger(configuration, "processId");
                if (processId != null) {
                    return session.attach(processId, progressMonitor);
                } else {
                    // Attach via port/host
                    return session.launch(configuration, actualDebugMode, OperationActor.AGENT, progressMonitor, serverEntry);
                }
            } else {
                // Launch mode (default)
                return session.launch(configuration, actualDebugMode, OperationActor.AGENT, progressMonitor, serverEntry);
            }
        }).thenApply(result -> {
            // Add sessionId to result
            result.put("sessionId", sessionId);
            result.put("language", session.getLanguage());

            // Expose disassembly-related capabilities
            Capabilities caps = session.getDapServer() != null && session.getDapServer().getDapClient() != null
                    ? session.getDapServer().getDapClient().getCapabilities() : null;
            if (caps != null) {
                Map<String, Object> disassemblyCapabilities = new HashMap<>();
                disassemblyCapabilities.put("supportsDisassembleRequest",
                        Boolean.TRUE.equals(caps.getSupportsDisassembleRequest()));
                disassemblyCapabilities.put("supportsInstructionBreakpoints",
                        Boolean.TRUE.equals(caps.getSupportsInstructionBreakpoints()));
                disassemblyCapabilities.put("supportsSteppingGranularity",
                        Boolean.TRUE.equals(caps.getSupportsSteppingGranularity()));
                result.put("disassemblyCapabilities", disassemblyCapabilities);
            }

            if (session.getState() == DapSession.SessionState.TERMINATED) {
                result.put("success", false);
                result.put("state", "terminated");
                String consoleOutput = session.getProgramOutput().getAllWithCategories();
                if (consoleOutput != null && !consoleOutput.isEmpty()) {
                    result.put("message", consoleOutput);
                } else {
                    result.put("message", "Program terminated immediately after launch");
                }
            }

            return result;
        }).whenComplete((result, ex) -> {
            progressMonitor.setComplete();
            if (ex != null) {
                String errorMessage = ToolException.resolveErrorMessage(ex);
                serverEntry.fail(errorMessage);
                operationContext.fail(errorMessage);
            } else {
                String createdSessionId = (String) result.get("sessionId");
                if (createdSessionId != null) {
                    operationContext.setSessionId(createdSessionId);
                    try {
                        operationContext.setSessionName(
                                sessionManager.getSession(createdSessionId).getSessionName());
                    } catch (Exception ignored) {
                    }
                }
                operationContext.setResult(String.valueOf(result));
                operationContext.complete();
            }
        }).exceptionally(ToolException::rethrow);
    }

    @Tool(description = "Detach from the debugged process without terminating it.")
    public Map<String, Object> detach_from_process(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () ->
                closeDebugSession(sessionId).join());
    }

    // ========== Execution Control ==========

    @Tool(description = "Continue program execution after hitting a breakpoint or pause. Returns console output (stdout/stderr) accumulated during execution.")
    public Map<String, Object> continue_execution(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () -> {
            DapSession session = sessionManager.getSession(sessionId);
            return session.continueExecution().join();
        });
    }

    @Tool(description = "Pause the running program at the current line.")
    public Map<String, Object> pause_execution(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () -> {
            DapSession session = sessionManager.getSession(sessionId);
            session.pause().join();
            return Map.of(
                    "success", true,
                    "message", "Execution paused"
            );
        });
    }

    @Tool(
            name = "step_over",
            description = "Step over the current line (execute without entering function calls). " +
                    "Optionally specify granularity: 'statement' (default) or 'instruction' for assembly-level stepping. " +
                    "Returns console output if any was printed during execution.")
    public Map<String, Object> stepOverSync(
            String sessionId,
            @ToolArg(description = "Optional stepping granularity: 'statement' (default), 'line', or 'instruction'", required = false) String granularity) {
        return tracked(buildArgs("sessionId", sessionId, "granularity", granularity), () ->
                stepOver(sessionId, granularity).join());
    }

    public CompletableFuture<Map<String, Object>> stepOver(String sessionId, String granularity) {
        DapSession session = sessionManager.getSession(sessionId);
        return session.stepOver(parseGranularity(granularity));
    }

    @Tool(
            name = "step_in",
            description = "Step into a function call on the current line. " +
                    "Optionally specify granularity: 'statement' (default) or 'instruction' for assembly-level stepping.")
    public Map<String, Object> stepInSync(
            String sessionId,
            @ToolArg(description = "Optional stepping granularity: 'statement' (default), 'line', or 'instruction'", required = false) String granularity) {
        return tracked(buildArgs("sessionId", sessionId, "granularity", granularity), () ->
                stepIn(sessionId, granularity).join());
    }

    public CompletableFuture<Map<String, Object>> stepIn(String sessionId, String granularity) {
        DapSession session = sessionManager.getSession(sessionId);
        return session.stepIn(parseGranularity(granularity));
    }

    @Tool(
            name = "step_out",
            description = "Step out of the current function, returning to the caller. " +
                    "Optionally specify granularity: 'statement' (default) or 'instruction' for assembly-level stepping.")
    public Map<String, Object> stepOutSync(
            String sessionId,
            @ToolArg(description = "Optional stepping granularity: 'statement' (default), 'line', or 'instruction'", required = false) String granularity) {
        return tracked(buildArgs("sessionId", sessionId, "granularity", granularity), () ->
                stepOut(sessionId, granularity).join());
    }

    public CompletableFuture<Map<String, Object>> stepOut(String sessionId, String granularity) {
        DapSession session = sessionManager.getSession(sessionId);
        return session.stepOut(parseGranularity(granularity));
    }

    private SteppingGranularity parseGranularity(String granularity) {
        if (granularity == null || granularity.isEmpty()) {
            return null;
        }
        return switch (granularity.toLowerCase()) {
            case "instruction" -> SteppingGranularity.INSTRUCTION;
            case "statement" -> SteppingGranularity.STATEMENT;
            case "line" -> SteppingGranularity.LINE;
            default -> null;
        };
    }

    // ========== Inspection ==========

    @Tool(description = "Get the current call stack (stack trace) showing function calls and line numbers.", structuredContent = true)
    public Map<String, Object> get_stack_trace(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () -> {
            DapSession session = sessionManager.getSession(sessionId);
            StackFrame[] frames = session.getStackTrace().join();

            List<Map<String, Object>> framesList = Arrays.stream(frames)
                    .map(frame -> {
                        Map<String, Object> frameMap = new HashMap<>();
                        frameMap.put("id", frame.getId());
                        frameMap.put("name", frame.getName());
                        frameMap.put("line", frame.getLine());
                        frameMap.put("column", frame.getColumn());
                        if (frame.getSource() != null) {
                            frameMap.put("file", frame.getSource().getPath());
                        }
                        if (frame.getInstructionPointerReference() != null) {
                            frameMap.put("instructionPointerReference", frame.getInstructionPointerReference());
                        }
                        return frameMap;
                    })
                    .toList();

            return Map.of(
                    "success", true,
                    "frames", framesList
            );
        });
    }

    // ========== Disassembly ==========

    @Tool(
            name = "disassemble",
            description = "Disassemble instructions at a memory address. " +
                    "Use instructionPointerReference from get_stack_trace as the memoryReference. " +
                    "Returns disassembled instructions with addresses, instruction text, and source locations. " +
                    "Requires adapter support (supportsDisassembleRequest capability).",
            structuredContent = true)
    public Map<String, Object> disassembleSync(
            @ToolArg(description = "Debug session ID") String sessionId,
            @ToolArg(description = "Memory reference to disassemble from (use instructionPointerReference from stack frame)") String memoryReference,
            @ToolArg(description = "Optional byte offset from the memory reference", required = false) Integer offset,
            @ToolArg(description = "Optional offset in instructions (negative=before, positive=after)", required = false) Integer instructionOffset,
            @ToolArg(description = "Number of instructions to disassemble (default: 50)", required = false) Integer instructionCount) {
        return tracked(buildArgs("sessionId", sessionId, "memoryReference", memoryReference,
                "offset", offset, "instructionOffset", instructionOffset, "instructionCount", instructionCount), () ->
                disassemble(sessionId, memoryReference, offset, instructionOffset, instructionCount).join());
    }

    public CompletableFuture<Map<String, Object>> disassemble(
            String sessionId, String memoryReference, Integer offset,
            Integer instructionOffset, Integer instructionCount) {
        DapSession session = sessionManager.getSession(sessionId);
        return session.disassemble(memoryReference, offset, instructionOffset, instructionCount)
                .thenApply(instructions -> {
                    List<Map<String, Object>> instrList = Arrays.stream(instructions)
                            .map(instr -> {
                                Map<String, Object> map = new HashMap<>();
                                map.put("address", instr.getAddress());
                                map.put("instruction", instr.getInstruction());
                                if (instr.getInstructionBytes() != null) {
                                    map.put("instructionBytes", instr.getInstructionBytes());
                                }
                                if (instr.getSymbol() != null) {
                                    map.put("symbol", instr.getSymbol());
                                }
                                if (instr.getLocation() != null) {
                                    map.put("sourceFile", instr.getLocation().getPath());
                                }
                                if (instr.getLine() != null) {
                                    map.put("line", instr.getLine());
                                }
                                if (instr.getColumn() != null) {
                                    map.put("column", instr.getColumn());
                                }
                                if (instr.getEndLine() != null) {
                                    map.put("endLine", instr.getEndLine());
                                }
                                return map;
                            })
                            .toList();

                    return Map.of(
                            "success", true,
                            "instructions", instrList,
                            "count", instrList.size()
                    );
                });
    }

    @Tool(description = "Get the console output (stdout/stderr/console.log) from the debugged program. Use this to see what the program has printed or logged. Very useful to understand program behavior without re-running. Returns up to 200 recent lines.")
    public Map<String, Object> get_console_output(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () -> {
            DapSession session = sessionManager.getSession(sessionId);

            String output = session.getProgramOutput().getAllWithCategories();
            int lineCount = session.getProgramOutput().getLineCount();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);

            if (lineCount > 0) {
                result.put("output", output);
                result.put("lines", lineCount);
            } else {
                result.put("output", "");
                result.put("lines", 0);
                result.put("message", "No console output yet");
            }

            return result;
        });
    }

    @Tool(description = "List all threads in the debugged program.", structuredContent = true)
    public Map<String, Object> list_threads(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () -> {
            DapSession session = sessionManager.getSession(sessionId);
            Thread[] threads = session.getThreads().join();

            List<Map<String, Object>> threadsList = Arrays.stream(threads)
                    .map(thread -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", thread.getId());
                        map.put("name", thread.getName());
                        return map;
                    })
                    .toList();

            return Map.of(
                    "success", true,
                    "threads", threadsList
            );
        });
    }

    @Tool(description = "Get variable scopes (Locals, Globals, etc.) for a specific stack frame.", structuredContent = true)
    public Map<String, Object> get_scopes(
            String sessionId,
            int frameId) {
        return tracked(buildArgs("sessionId", sessionId, "frameId", frameId), () -> {
            DapSession session = sessionManager.getSession(sessionId);
            Scope[] scopes = session.getScopes(frameId).join();

            List<Map<String, Object>> scopesList = Arrays.stream(scopes)
                    .map(scope -> {
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", scope.getName());
                        map.put("variablesReference", scope.getVariablesReference());
                        map.put("expensive", scope.isExpensive());
                        return map;
                    })
                    .toList();

            return Map.of(
                    "success", true,
                    "scopes", scopesList
            );
        });
    }

    @Tool(description = "Get variables from a scope or expandable variable. " +
            "Use variablesReference from get_scopes or a variable's variablesReference.",
            structuredContent = true)
    public Map<String, Object> get_variables(
            String sessionId,
            int variablesReference) {
        return tracked(buildArgs("sessionId", sessionId, "variablesReference", variablesReference), () ->
                fetchVariables(sessionId, variablesReference));
    }

    private Map<String, Object> fetchVariables(String sessionId, int variablesReference) {
        DapSession session = sessionManager.getSession(sessionId);
        Variable[] variables = session.getVariables(variablesReference).join();

        List<Map<String, Object>> varsList = Arrays.stream(variables)
                .map(var -> {
                    Map<String, Object> varMap = new HashMap<>();
                    varMap.put("name", var.getName());
                    varMap.put("value", var.getValue());
                    varMap.put("type", var.getType() != null ? var.getType() : "");
                    varMap.put("variablesReference", var.getVariablesReference());
                    varMap.put("expandable", var.getVariablesReference() > 0);
                    return varMap;
                })
                .toList();

        return Map.of(
                "success", true,
                "variables", varsList,
                "count", varsList.size()
        );
    }

    @Tool(description = "Shortcut to get local variables in the current stack frame (top of stack).", structuredContent = true)
    public Map<String, Object> get_local_variables(String sessionId) {
        return tracked(buildArgs("sessionId", sessionId), () -> {
            DapSession session = sessionManager.getSession(sessionId);

            // Get top frame
            StackFrame[] frames = session.getStackTrace().join();
            if (frames.length == 0) {
                throw new ToolException("No stack frames available");
            }

            int frameId = frames[0].getId();

            // Get scopes for top frame
            Scope[] scopes = session.getScopes(frameId).join();

            // Find "Locals" scope
            Scope localsScope = Arrays.stream(scopes)
                    .filter(s -> "Locals".equalsIgnoreCase(s.getName()))
                    .findFirst()
                    .orElse(scopes.length > 0 ? scopes[0] : null);

            if (localsScope == null) {
                throw new ToolException("No local scope found");
            }

            // Get variables from locals scope
            return fetchVariables(sessionId, localsScope.getVariablesReference());
        });
    }

    @Tool(
            name = "evaluate_expression",
            description = "Evaluate an expression in the current debug context (e.g., 'x + y', 'myFunction()').",
            structuredContent = true)
    public EvaluateResponse evaluateExpressionSync(
            @ToolArg(description = "The debug session ID") String sessionId,
            @ToolArg(description = "Expression to evaluate (e.g., 'x + y', 'myFunction()')") String expression,
            @ToolArg(description = "Stack frame ID for context (uses top frame if not provided)", required = false) Integer frameId,
            Cancellation cancellation) {
        return tracked(buildArgs("sessionId", sessionId, "expression", expression, "frameId", frameId), () ->
                evaluateExpression(sessionId, expression, frameId, cancellation).join());
    }

    public CompletableFuture<EvaluateResponse> evaluateExpression(
            String sessionId,
            String expression,
            Integer frameId,
            Cancellation cancellation) {

        DapSession session = sessionManager.getSession(sessionId);

        // If no frameId provided, use top frame
        if (frameId == null) {
            return executeWithCancellation(
                    session.getStackTrace()
                            .thenCompose(frames -> {
                                int targetFrameId = frames.length > 0 ? frames[0].getId() : 0;
                                return session.evaluate(expression, targetFrameId);
                            }),
                    cancellation
            );
        }

        return executeWithCancellation(session.evaluate(expression, frameId), cancellation);
    }

    // ========== Statistics ==========

    @Tool(description = "Get statistics about active debug sessions (total count, states, supported languages).")
    public Map<String, Object> get_debug_statistics() {
        return tracked(buildArgs(), () -> sessionManager.getStatistics());
    }

    // ========== Configuration Helpers ==========

    @Tool(
            name = "get_debug_templates",
            description = "Get debug configuration templates for a specific debug adapter. " +
                    "Returns templates grouped by type (launch, attach) from the debug adapter's configuration. " +
                    "Use the adapter ID from list_debug_adapters.",
            structuredContent = true
    )
    public DebugTemplatesResult getDebugTemplates(
            @ToolArg(description = "ID of the debug adapter (e.g., 'java-debug', 'vscode-js-debug')") String debuggerId) {
        return tracked(buildArgs("debuggerId", debuggerId), () -> {
            // Get the real configuration templates from the debug adapter config
            var serverConfig = application.getDapServerConfig(debuggerId);
            if (serverConfig == null) {
                throw new ToolException("Unknown debug adapter: " + debuggerId + ". Use list_debug_adapters to see available adapters.");
            }

            var allTemplates = serverConfig.getConfigurationTemplates();

            // Group templates by type (launch vs attach)
            var launchTemplates = allTemplates.stream()
                    .filter(t -> t.name().startsWith("launch."))
                    .toList();

            var attachTemplates = allTemplates.stream()
                    .filter(t -> t.name().startsWith("attach."))
                    .toList();

            return new DebugTemplatesResult(debuggerId, launchTemplates, attachTemplates, null);
        });
    }

    /**
     * Result class for debug templates, grouped by type.
     */
    public static class DebugTemplatesResult {
        public String debuggerId;
        public List<DapConfigurationTemplate> launch;
        public List<DapConfigurationTemplate> attach;
        public String error;

        public DebugTemplatesResult(
                String debuggerId,
                List<DapConfigurationTemplate> launch,
                List<DapConfigurationTemplate> attach,
                String error) {
            this.debuggerId = debuggerId;
            this.launch = launch;
            this.attach = attach;
            this.error = error;
        }
    }

}
