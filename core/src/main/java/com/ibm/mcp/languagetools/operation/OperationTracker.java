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
package com.ibm.mcp.languagetools.operation;

import com.ibm.mcp.languagetools.configuration.ApplicationConfiguration;
import io.quarkiverse.mcp.server.Tool;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@ApplicationScoped
public class OperationTracker {

    private static final String SETTING_KEY = "activity.enabled";
    private static final int MAX_OPERATIONS = 500;

    @Inject
    ApplicationConfiguration configuration;

    private volatile boolean enabled;
    private final ConcurrentLinkedDeque<OperationContext> operations = new ConcurrentLinkedDeque<>();
    private final List<Consumer<OperationEvent>> listeners = new CopyOnWriteArrayList<>();

    @PostConstruct
    void init() {
        enabled = configuration.getBoolean(SETTING_KEY, false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        configuration.set(SETTING_KEY, enabled);
        if (!enabled) {
            operations.clear();
        }
    }

    public OperationContext startOperation(String name, String kind, String workspaceUri) {
        if (!enabled) {
            return OperationContext.noop();
        }
        OperationContext ctx = new OperationContext(name, kind, workspaceUri, this);
        operations.addLast(ctx);
        while (operations.size() > MAX_OPERATIONS) {
            operations.pollFirst();
        }
        fireEvent(new OperationEvent(OperationEvent.Type.STARTED, ctx));
        return ctx;
    }

    void operationUpdated(OperationContext ctx) {
        fireEvent(new OperationEvent(OperationEvent.Type.UPDATED, ctx));
    }

    void operationCompleted(OperationContext ctx) {
        fireEvent(new OperationEvent(OperationEvent.Type.COMPLETED, ctx));
    }

    public void addListener(Consumer<OperationEvent> listener) {
        listeners.add(listener);
    }

    public List<OperationContext> getRecentOperations(int limit) {
        var all = operations.stream().toList();
        int skip = Math.max(0, all.size() - limit);
        return all.stream().skip(skip).toList();
    }

    private void fireEvent(OperationEvent event) {
        for (Consumer<OperationEvent> listener : listeners) {
            listener.accept(event);
        }
    }

    private static final Map<String, String> TOOL_NAME_CACHE = new ConcurrentHashMap<>();

    public static String resolveToolName(String fallback) {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .limit(10)
                        .map(frame -> {
                            String key = frame.getClassName() + "#" + frame.getMethodName();
                            String cached = TOOL_NAME_CACHE.get(key);
                            if (cached != null) {
                                return cached;
                            }
                            try {
                                for (Method m : frame.getDeclaringClass().getDeclaredMethods()) {
                                    if (m.getName().equals(frame.getMethodName())) {
                                        Tool tool = m.getAnnotation(Tool.class);
                                        if (tool != null) {
                                            String name = Tool.ELEMENT_NAME.equals(tool.name())
                                                    ? m.getName() : tool.name();
                                            TOOL_NAME_CACHE.put(key, name);
                                            return name;
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                // ignore
                            }
                            return null;
                        })
                        .filter(name -> name != null)
                        .findFirst()
                        .orElse(fallback));
    }
}
