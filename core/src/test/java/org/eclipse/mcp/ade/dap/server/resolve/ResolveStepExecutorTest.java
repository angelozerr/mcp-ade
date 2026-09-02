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
package org.eclipse.mcp.ade.dap.server.resolve;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class ResolveStepExecutorTest {

    private List<String> traces;
    private String lastCommandName;
    private Object commandResult;

    private ResolveStepExecutor executor;

    @BeforeEach
    void setUp() {
        traces = new ArrayList<>();
        lastCommandName = null;
        commandResult = Map.of();

        BiFunction<String, Object, CompletableFuture<?>> requestSender = (command, args) -> {
            lastCommandName = command;
            return CompletableFuture.completedFuture(commandResult);
        };

        executor = new ResolveStepExecutor(requestSender, traces::add);
    }

    private ResolveStepExecutor createExecutorWithSender(
            BiFunction<String, Object, CompletableFuture<?>> sender) {
        return new ResolveStepExecutor(sender, traces::add);
    }

    // ===== Variable resolution in args =====

    @Test
    void resolveArgs_simpleStringVariable() {
        Map<String, Object> config = new HashMap<>();
        config.put("mainClass", "com.example.Main");

        List<Object> args = List.of("${mainClass}");
        List<Object> resolved = executor.resolveArgs(args, config);

        assertEquals(1, resolved.size());
        assertEquals("com.example.Main", resolved.get(0));
    }

    @Test
    void resolveArgs_objectWithVariable() {
        Map<String, Object> config = new HashMap<>();
        config.put("uri", "file:///path/Main.java");

        List<Object> args = List.of(Map.of("uri", "${uri}"));
        List<Object> resolved = executor.resolveArgs(args, config);

        assertEquals(1, resolved.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> resolvedMap = (Map<String, Object>) resolved.get(0);
        assertEquals("file:///path/Main.java", resolvedMap.get("uri"));
    }

    @Test
    void resolveArgs_preservesNullLiterals() {
        Map<String, Object> config = new HashMap<>();
        config.put("mainClass", "com.example.Main");

        List<Object> args = new ArrayList<>();
        args.add("${mainClass}");
        args.add(null);

        List<Object> resolved = executor.resolveArgs(args, config);

        assertEquals(2, resolved.size());
        assertEquals("com.example.Main", resolved.get(0));
        assertNull(resolved.get(1));
    }

    @Test
    void resolveArgs_preservesNonStringValues() {
        Map<String, Object> config = new HashMap<>();

        List<Object> args = List.of("literal", 42, true);
        List<Object> resolved = executor.resolveArgs(args, config);

        assertEquals(3, resolved.size());
        assertEquals("literal", resolved.get(0));
        assertEquals(42, resolved.get(1));
        assertEquals(true, resolved.get(2));
    }

    @Test
    void resolveArgs_preservesVariableType() {
        Map<String, Object> config = new HashMap<>();
        config.put("myList", List.of("a", "b", "c"));

        List<Object> args = List.of("${myList}");
        List<Object> resolved = executor.resolveArgs(args, config);

        assertEquals(List.of("a", "b", "c"), resolved.get(0));
    }

    @Test
    void resolveArgs_throwsOnMissingVariable() {
        Map<String, Object> config = new HashMap<>();

        List<Object> args = List.of("${missingVar}");

        assertThrows(ResolveStepExecutor.UnresolvedVariableException.class,
                () -> executor.resolveArgs(args, config));
    }

    @Test
    void resolveArgs_stringInterpolation() {
        Map<String, Object> config = new HashMap<>();
        config.put("host", "localhost");
        config.put("port", "8080");

        List<Object> args = List.of("http://${host}:${port}/api");
        List<Object> resolved = executor.resolveArgs(args, config);

        assertEquals("http://localhost:8080/api", resolved.get(0));
    }

    // ===== Returns mapping =====

    @Test
    void applyReturns_objectFieldExtraction() {
        Map<String, String> returns = new LinkedHashMap<>();
        returns.put("classPaths", "$classpath");
        returns.put("moduleName", "$moduleName");

        Object result = Map.of(
                "classpath", List.of("a.jar", "b.jar"),
                "moduleName", "myModule"
        );

        Map<String, Object> config = new HashMap<>();
        executor.applyReturns(returns, result, config);

        assertEquals(List.of("a.jar", "b.jar"), config.get("classPaths"));
        assertEquals("myModule", config.get("moduleName"));
    }

    @Test
    void applyReturns_arrayIndexExtraction() {
        Map<String, String> returns = new LinkedHashMap<>();
        returns.put("modulePaths", "$[0]");
        returns.put("classPaths", "$[1]");

        Object result = List.of(
                List.of("module1.jar"),
                List.of("class1.jar", "class2.jar")
        );

        Map<String, Object> config = new HashMap<>();
        executor.applyReturns(returns, result, config);

        assertEquals(List.of("module1.jar"), config.get("modulePaths"));
        assertEquals(List.of("class1.jar", "class2.jar"), config.get("classPaths"));
    }

    @Test
    void applyReturns_rawResult() {
        Map<String, String> returns = Map.of("javaExec", "$");

        Object result = "/usr/bin/java";

        Map<String, Object> config = new HashMap<>();
        executor.applyReturns(returns, result, config);

        assertEquals("/usr/bin/java", config.get("javaExec"));
    }

    @Test
    void applyReturns_skipsNullValues() {
        Map<String, String> returns = Map.of("missing", "$nonexistent");

        Object result = Map.of("other", "value");

        Map<String, Object> config = new HashMap<>();
        executor.applyReturns(returns, result, config);

        assertFalse(config.containsKey("missing"));
    }

    @Test
    void applyReturns_emptyReturns() {
        Map<String, Object> config = new HashMap<>();
        config.put("existing", "value");

        executor.applyReturns(Map.of(), "result", config);

        assertEquals(1, config.size());
        assertEquals("value", config.get("existing"));
    }

    // ===== Full step execution =====

    @Test
    void execute_singleStep() throws Exception {
        commandResult = Map.of("uri", "file:///path/Main.java");

        ResolveStepConfig step = new ResolveStepConfig(
                "intellij.java.resolveClassDocument",
                List.of(Map.of("fqn", "${mainClass}")),
                Map.of("uri", "$uri"),
                false
        );

        Map<String, Object> config = new HashMap<>();
        config.put("mainClass", "com.example.Main");

        Map<String, Object> result = executor.execute(List.of(step), config).get();

        assertEquals("file:///path/Main.java", result.get("uri"));
        assertEquals("intellij.java.resolveClassDocument", lastCommandName);
    }

    @Test
    void execute_chainedSteps() throws Exception {
        BiFunction<String, Object, CompletableFuture<?>> sender = (command, args) -> {
            if (command.equals("resolveDocument")) {
                return CompletableFuture.completedFuture(Map.of("uri", "file:///Main.java"));
            }
            if (command.equals("resolveClasspath")) {
                return CompletableFuture.completedFuture(Map.of(
                        "classpath", List.of("a.jar"),
                        "modulePath", List.of("m.jar")
                ));
            }
            return CompletableFuture.completedFuture(Map.of());
        };

        ResolveStepExecutor exec = createExecutorWithSender(sender);

        List<ResolveStepConfig> steps = List.of(
                new ResolveStepConfig("resolveDocument",
                        List.of(Map.of("fqn", "${mainClass}")),
                        Map.of("uri", "$uri"), false),
                new ResolveStepConfig("resolveClasspath",
                        List.of(Map.of("uri", "${uri}")),
                        Map.of("classPaths", "$classpath", "modulePaths", "$modulePath"), false)
        );

        Map<String, Object> config = new HashMap<>();
        config.put("mainClass", "com.example.Main");

        Map<String, Object> result = exec.execute(steps, config).get();

        assertEquals("file:///Main.java", result.get("uri"));
        assertEquals(List.of("a.jar"), result.get("classPaths"));
        assertEquals(List.of("m.jar"), result.get("modulePaths"));
    }

    @Test
    void execute_skipsWhenAllReturnsPresent() throws Exception {
        ResolveStepConfig step = new ResolveStepConfig(
                "resolveClasspath",
                List.of(Map.of("uri", "${uri}")),
                Map.of("classPaths", "$classpath"),
                false
        );

        Map<String, Object> config = new HashMap<>();
        config.put("uri", "file:///Main.java");
        config.put("classPaths", List.of("already-provided.jar"));

        executor.execute(List.of(step), config).get();

        assertNull(lastCommandName);
        assertTrue(traces.stream().anyMatch(t -> t.contains("Skipping")));
    }

    @Test
    void execute_failsOnMissingVariable() {
        ResolveStepConfig step = new ResolveStepConfig(
                "resolveClasspath",
                List.of(Map.of("uri", "${uri}")),
                Map.of("classPaths", "$classpath"),
                false
        );

        Map<String, Object> config = new HashMap<>();

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> executor.execute(List.of(step), config).get());
        assertTrue(ex.getCause().getMessage().contains("uri"));
    }

    @Test
    void execute_optionalStepSkipsOnMissingVariable() throws Exception {
        ResolveStepConfig step = new ResolveStepConfig(
                "resolveWorkingDirectory",
                List.of(Map.of("uri", "${uri}")),
                Map.of("cwd", "$workingDirectory"),
                true
        );

        Map<String, Object> config = new HashMap<>();

        Map<String, Object> result = executor.execute(List.of(step), config).get();

        assertNull(lastCommandName);
        assertFalse(result.containsKey("cwd"));
    }

    @Test
    void execute_optionalStepContinuesOnCommandError() throws Exception {
        BiFunction<String, Object, CompletableFuture<?>> failingSender = (command, args) ->
                CompletableFuture.failedFuture(new RuntimeException("Server error"));

        ResolveStepExecutor exec = createExecutorWithSender(failingSender);

        ResolveStepConfig step = new ResolveStepConfig(
                "resolveWorkingDirectory",
                List.of(Map.of("uri", "${uri}")),
                Map.of("cwd", "$workingDirectory"),
                true
        );

        Map<String, Object> config = new HashMap<>();
        config.put("uri", "file:///Main.java");

        Map<String, Object> result = exec.execute(List.of(step), config).get();

        assertFalse(result.containsKey("cwd"));
        assertTrue(traces.stream().anyMatch(t -> t.contains("optional")));
    }

    @Test
    void execute_nonOptionalStepFailsOnCommandError() {
        BiFunction<String, Object, CompletableFuture<?>> failingSender = (command, args) ->
                CompletableFuture.failedFuture(new RuntimeException("Server error"));

        ResolveStepExecutor exec = createExecutorWithSender(failingSender);

        ResolveStepConfig step = new ResolveStepConfig(
                "resolveClasspath",
                List.of(Map.of("uri", "${uri}")),
                Map.of("classPaths", "$classpath"),
                false
        );

        Map<String, Object> config = new HashMap<>();
        config.put("uri", "file:///Main.java");

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> exec.execute(List.of(step), config).get());
        assertTrue(ex.getCause().getMessage().contains("resolveClasspath"));
    }

    @Test
    void execute_stepWithNoReturns() throws Exception {
        commandResult = "ok";

        ResolveStepConfig step = new ResolveStepConfig(
                "validateLaunchConfig",
                List.of("${mainClass}"),
                Map.of(),
                false
        );

        Map<String, Object> config = new HashMap<>();
        config.put("mainClass", "com.example.Main");

        Map<String, Object> result = executor.execute(List.of(step), config).get();

        assertEquals("validateLaunchConfig", lastCommandName);
        assertEquals("com.example.Main", result.get("mainClass"));
    }

    // ===== Context variables =====

    @Test
    void execute_contextVariableResolvedInArgs() throws Exception {
        commandResult = "ok";

        ResolveStepConfig step = new ResolveStepConfig(
                "validateLaunchConfig",
                List.of("${workspaceUri}", "${mainClass}"),
                Map.of(),
                false
        );

        Map<String, Object> config = new HashMap<>();
        config.put("mainClass", "com.example.Main");

        Map<String, Object> context = Map.of("workspaceUri", "file:///workspace");

        Map<String, Object> result = executor.execute(List.of(step), config, context).get();

        assertEquals("validateLaunchConfig", lastCommandName);
        assertFalse(result.containsKey("workspaceUri"), "context variable must not leak into launchConfig");
    }

    @Test
    void execute_launchConfigTakesPrecedenceOverContext() throws Exception {
        commandResult = "ok";

        ResolveStepConfig step = new ResolveStepConfig(
                "someCommand",
                List.of("${workspaceUri}"),
                Map.of(),
                false
        );

        Map<String, Object> config = new HashMap<>();
        config.put("workspaceUri", "file:///user-provided");

        Map<String, Object> context = Map.of("workspaceUri", "file:///default");

        executor.execute(List.of(step), config, context).get();

        @SuppressWarnings("unchecked")
        List<Object> sentArgs = (List<Object>) executor.resolveArgs(
                List.of("${workspaceUri}"),
                new LinkedHashMap<>(context) {{ putAll(config); }}
        );
        assertEquals("file:///user-provided", sentArgs.get(0));
    }

    @Test
    void execute_contextVariableUsedAcrossChainedSteps() throws Exception {
        BiFunction<String, Object, CompletableFuture<?>> sender = (command, args) -> {
            if (command.equals("validate")) {
                return CompletableFuture.completedFuture("ok");
            }
            if (command.equals("resolveClasspath")) {
                return CompletableFuture.completedFuture(List.of(
                        List.of("module.jar"),
                        List.of("class.jar")
                ));
            }
            return CompletableFuture.completedFuture(Map.of());
        };

        ResolveStepExecutor exec = createExecutorWithSender(sender);

        List<ResolveStepConfig> steps = List.of(
                new ResolveStepConfig("validate",
                        List.of("${workspaceUri}", "${mainClass}"),
                        Map.of(), false),
                new ResolveStepConfig("resolveClasspath",
                        List.of("${mainClass}"),
                        Map.of("modulePaths", "$[0]", "classPaths", "$[1]"), false)
        );

        Map<String, Object> config = new HashMap<>();
        config.put("mainClass", "com.example.Main");

        Map<String, Object> context = Map.of("workspaceUri", "file:///workspace");

        Map<String, Object> result = exec.execute(steps, config, context).get();

        assertEquals(List.of("module.jar"), result.get("modulePaths"));
        assertEquals(List.of("class.jar"), result.get("classPaths"));
        assertFalse(result.containsKey("workspaceUri"), "context variable must not leak into launchConfig");
    }
}
