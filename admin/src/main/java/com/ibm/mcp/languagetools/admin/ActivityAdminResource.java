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
package com.ibm.mcp.languagetools.admin;

import com.ibm.mcp.languagetools.admin.ws.ActivityStateWsMessage;
import com.ibm.mcp.languagetools.operation.OperationActor;
import com.ibm.mcp.languagetools.operation.OperationTracker;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@ApplicationScoped
@Path("/api/admin/activity")
@Produces(MediaType.APPLICATION_JSON)
public class ActivityAdminResource {

    private static final Logger LOG = Logger.getLogger(ActivityAdminResource.class);

    @Inject
    OperationTracker operationTracker;

    @Inject
    ToolManager toolManager;

    @Inject
    Event<ActivityStateWsMessage> activityStateEvent;

    @PUT
    @Path("/enabled")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> setEnabled(Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        operationTracker.setEnabled(enabled);
        activityStateEvent.fire(new ActivityStateWsMessage(enabled));
        return Map.of("enabled", enabled);
    }

    @GET
    @Path("/enabled")
    public Map<String, Boolean> getEnabled() {
        return Map.of("enabled", operationTracker.isEnabled());
    }

    private final Map<String, ReplayCancellation> activeReplays = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @POST
    @Path("/replay")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response replay(Map<String, Object> body) {
        String toolName = (String) body.get("toolName");
        Map<String, Object> arguments = (Map<String, Object>) body.get("arguments");

        if (toolName == null) {
            return Response.status(400).entity(Map.of("error", "toolName is required")).build();
        }

        ToolManager.ToolInfo tool = toolManager.getTool(toolName);
        if (tool == null || tool.method().isEmpty()) {
            return Response.status(404).entity(Map.of("error", "Tool not found: " + toolName)).build();
        }

        Method method = tool.method().get();
        Object bean = Arc.container().instance(method.getDeclaringClass()).get();

        String replayId = UUID.randomUUID().toString();
        ReplayCancellation cancellation = new ReplayCancellation();
        activeReplays.put(replayId, cancellation);

        List<ToolManager.ToolArgument> toolArgs = tool.arguments();
        Object[] invokeArgs = buildInvocationArgs(method, toolArgs, arguments, cancellation);

        CompletableFuture.runAsync(() -> {
            OperationTracker.setCurrentActor(OperationActor.USER);
            try {
                Object result = method.invoke(bean, invokeArgs);
                if (result instanceof CompletableFuture<?> future) {
                    future.whenComplete((r, ex) -> {
                        activeReplays.remove(replayId);
                        if (ex != null) {
                            LOG.errorf(ex, "Replay failed for tool %s", toolName);
                        }
                    });
                } else {
                    activeReplays.remove(replayId);
                }
            } catch (Exception e) {
                activeReplays.remove(replayId);
                LOG.errorf(e, "Replay invocation failed for tool %s", toolName);
            } finally {
                OperationTracker.clearCurrentActor();
            }
        });

        return Response.ok(Map.of("replayId", replayId)).build();
    }

    @DELETE
    @Path("/replay/{replayId}")
    public Response cancelReplay(@PathParam("replayId") String replayId) {
        ReplayCancellation cancellation = activeReplays.remove(replayId);
        if (cancellation == null) {
            return Response.status(404).entity(Map.of("error", "Replay not found or already completed")).build();
        }
        cancellation.cancel("Cancelled by user");
        return Response.ok(Map.of("cancelled", true)).build();
    }

    private static class ReplayCancellation implements Cancellation {
        private volatile boolean cancelled;
        private volatile String reason;
        private final List<Consumer<Optional<String>>> listeners = new CopyOnWriteArrayList<>();

        @Override
        public Result check() {
            return new Result(cancelled, Optional.ofNullable(reason));
        }

        @Override
        public void onCancelled(Consumer<Optional<String>> action) {
            if (cancelled) {
                action.accept(Optional.ofNullable(reason));
            } else {
                listeners.add(action);
            }
        }

        void cancel(String reason) {
            this.reason = reason;
            this.cancelled = true;
            Optional<String> r = Optional.ofNullable(reason);
            for (Consumer<Optional<String>> listener : listeners) {
                listener.accept(r);
            }
        }
    }

    private Object[] buildInvocationArgs(Method method, List<ToolManager.ToolArgument> toolArgs,
                                          Map<String, Object> arguments, Cancellation cancellation) {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];

        int argIdx = 0;
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();

            if (Cancellation.class.isAssignableFrom(type)) {
                args[i] = cancellation;
                continue;
            }
            if (Progress.class.isAssignableFrom(type)) {
                args[i] = null;
                continue;
            }

            if (argIdx < toolArgs.size()) {
                String argName = toolArgs.get(argIdx).name();
                Object value = arguments != null ? arguments.get(argName) : null;
                args[i] = convertArg(value, type);
                argIdx++;
            }
        }
        return args;
    }

    private Object convertArg(Object value, Class<?> type) {
        if (value == null) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == boolean.class) return false;
            return null;
        }
        if (type == int.class || type == Integer.class) {
            return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
        }
        if (type == long.class || type == Long.class) {
            return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
        }
        if (type == boolean.class || type == Boolean.class) {
            return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        return value;
    }
}
