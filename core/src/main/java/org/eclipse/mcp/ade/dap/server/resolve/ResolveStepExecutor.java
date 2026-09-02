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

import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Executes declarative resolve steps sequentially, resolving {@code ${variable}}
 * references in args from the launch configuration and mapping results back
 * via {@code $field} references in returns.
 *
 * <p>Skip rules:</p>
 * <ul>
 *   <li>If all target fields in {@code returns} are already present in the
 *       launch config, the step is skipped.</li>
 *   <li>If a {@code ${variable}} in args cannot be resolved, the step fails
 *       (or is skipped if {@code optional}).</li>
 * </ul>
 */
public class ResolveStepExecutor {

    private static final Logger LOG = Logger.getLogger(ResolveStepExecutor.class);

    private static final String VAR_PREFIX = "${";
    private static final String VAR_SUFFIX = "}";
    private static final String RESULT_PREFIX = "$";

    private final BiFunction<String, Object, CompletableFuture<?>> requestSender;
    private final Consumer<String> tracer;

    /**
     * @param requestSender function that sends a command via routeRequest(command, args)
     * @param tracer function to log trace messages
     */
    public ResolveStepExecutor(BiFunction<String, Object, CompletableFuture<?>> requestSender,
                                Consumer<String> tracer) {
        this.requestSender = requestSender;
        this.tracer = tracer;
    }

    /**
     * Execute all resolve steps sequentially, enriching the launch configuration.
     *
     * @param steps the resolve steps to execute
     * @param launchConfig the mutable launch configuration map
     * @return future that completes with the enriched launch config
     */
    public CompletableFuture<Map<String, Object>> execute(
            List<ResolveStepConfig> steps,
            Map<String, Object> launchConfig) {

        CompletableFuture<Map<String, Object>> future = CompletableFuture.completedFuture(launchConfig);

        for (ResolveStepConfig step : steps) {
            future = future.thenCompose(config -> executeStep(step, config));
        }

        return future;
    }

    private CompletableFuture<Map<String, Object>> executeStep(
            ResolveStepConfig step,
            Map<String, Object> launchConfig) {

        String command = step.getCommand();

        // Skip if all returns targets are already present
        if (shouldSkipByReturns(step, launchConfig)) {
            trace("Skipping %s — all target fields already present", command);
            return CompletableFuture.completedFuture(launchConfig);
        }

        // Resolve args
        List<Object> resolvedArgs;
        try {
            resolvedArgs = resolveArgs(step.getArgs(), launchConfig);
        } catch (UnresolvedVariableException e) {
            String msg = String.format("Cannot resolve ${%s} for command %s", e.getVariableName(), command);
            if (step.isOptional()) {
                trace("Skipping %s (optional) — %s", command, msg);
                return CompletableFuture.completedFuture(launchConfig);
            }
            return CompletableFuture.failedFuture(new IllegalStateException(msg));
        }

        trace("Executing %s", command);

        return requestSender.apply(command, resolvedArgs)
                .thenApply(result -> {
                    applyReturns(step.getReturns(), result, launchConfig);
                    return launchConfig;
                })
                .exceptionally(ex -> {
                    if (step.isOptional()) {
                        trace("Step %s failed (optional, continuing): %s", command, ex.getMessage());
                        return launchConfig;
                    }
                    throw new RuntimeException("Resolve step failed: " + command, ex);
                });
    }

    /**
     * Returns true if all target fields in the returns mapping are already present
     * in the launch config.
     */
    private boolean shouldSkipByReturns(ResolveStepConfig step, Map<String, Object> launchConfig) {
        Map<String, String> returns = step.getReturns();
        if (returns.isEmpty()) {
            return false;
        }
        for (String targetKey : returns.keySet()) {
            if (!launchConfig.containsKey(targetKey)) {
                return false;
            }
        }
        return true;
    }

    // ===== Variable resolution in args =====

    /**
     * Resolve {@code ${variable}} references in args from the launch config.
     * Walks the structure recursively (lists, maps, strings).
     *
     * @throws UnresolvedVariableException if a variable cannot be resolved
     */
    List<Object> resolveArgs(List<Object> args, Map<String, Object> launchConfig) {
        List<Object> resolved = new ArrayList<>(args.size());
        for (Object arg : args) {
            resolved.add(resolveValue(arg, launchConfig));
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Object resolveValue(Object value, Map<String, Object> launchConfig) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return resolveString(s, launchConfig);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                resolved.put(String.valueOf(entry.getKey()),
                        resolveValue(entry.getValue(), launchConfig));
            }
            return resolved;
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolveValue(item, launchConfig));
            }
            return resolved;
        }
        return value;
    }

    /**
     * Resolve a string that may contain {@code ${variable}} references.
     * If the entire string is a single {@code ${variable}}, returns the raw value
     * (preserving type). Otherwise does string interpolation.
     */
    private Object resolveString(String s, Map<String, Object> launchConfig) {
        // Full variable reference: "${mainClass}" → return raw value (preserves type)
        if (s.startsWith(VAR_PREFIX) && s.endsWith(VAR_SUFFIX) && s.indexOf(VAR_PREFIX, 2) == -1) {
            String varName = s.substring(VAR_PREFIX.length(), s.length() - VAR_SUFFIX.length());
            if (!launchConfig.containsKey(varName)) {
                throw new UnresolvedVariableException(varName);
            }
            return launchConfig.get(varName);
        }

        // String interpolation: "prefix ${var} suffix"
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        while (pos < s.length()) {
            int start = s.indexOf(VAR_PREFIX, pos);
            if (start == -1) {
                sb.append(s, pos, s.length());
                break;
            }
            sb.append(s, pos, start);
            int end = s.indexOf(VAR_SUFFIX, start + VAR_PREFIX.length());
            if (end == -1) {
                sb.append(s, start, s.length());
                break;
            }
            String varName = s.substring(start + VAR_PREFIX.length(), end);
            if (!launchConfig.containsKey(varName)) {
                throw new UnresolvedVariableException(varName);
            }
            Object val = launchConfig.get(varName);
            sb.append(val != null ? val.toString() : "");
            pos = end + VAR_SUFFIX.length();
        }
        return sb.toString();
    }

    // ===== Result mapping (returns) =====

    /**
     * Apply the returns mapping: extract fields from the result and put them
     * into the launch configuration.
     *
     * <p>Value syntax:</p>
     * <ul>
     *   <li>{@code $fieldName} — extract a named field from an object result</li>
     *   <li>{@code $[index]} — extract by index from an array result</li>
     *   <li>{@code $} — use the raw result as-is</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    void applyReturns(Map<String, String> returns, Object result, Map<String, Object> launchConfig) {
        if (returns == null || returns.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> entry : returns.entrySet()) {
            String targetKey = entry.getKey();
            String sourceRef = entry.getValue();

            Object value = extractFromResult(sourceRef, result);
            if (value != null) {
                launchConfig.put(targetKey, value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object extractFromResult(String sourceRef, Object result) {
        if (sourceRef == null || !sourceRef.startsWith(RESULT_PREFIX)) {
            return null;
        }

        String path = sourceRef.substring(RESULT_PREFIX.length());

        // "$" — raw result
        if (path.isEmpty()) {
            return result;
        }

        // "$[index]" — array access
        if (path.startsWith("[") && path.endsWith("]")) {
            try {
                int index = Integer.parseInt(path.substring(1, path.length() - 1));
                if (result instanceof List<?> list && index >= 0 && index < list.size()) {
                    return list.get(index);
                }
            } catch (NumberFormatException e) {
                LOG.warnf("Invalid array index in returns: %s", sourceRef);
            }
            return null;
        }

        // "$fieldName" — object field access
        if (result instanceof Map<?, ?> map) {
            return map.get(path);
        }

        return null;
    }

    private void trace(String format, Object... args) {
        if (tracer != null) {
            tracer.accept(String.format(format, args));
        }
    }

    /**
     * Exception thrown when a {@code ${variable}} cannot be resolved from the launch config.
     */
    public static class UnresolvedVariableException extends RuntimeException {
        private final String variableName;

        public UnresolvedVariableException(String variableName) {
            super("Unresolved variable: " + variableName);
            this.variableName = variableName;
        }

        public String getVariableName() {
            return variableName;
        }
    }
}
