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
package org.eclipse.mcp.ade.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class DefaultTraceCollector implements TraceCollector {

    private static final int MAX_TRACE_MESSAGES = 1000;
    private static final long FLUSH_INTERVAL_MS = 100;

    private final TraceKind traceKind;
    private final ConcurrentLinkedDeque<TraceMessage> traces = new ConcurrentLinkedDeque<>();
    private final AtomicInteger traceCount = new AtomicInteger();
    private final List<Consumer<TraceMessage>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<TraceMessage> pendingNotifications = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private static final ScheduledExecutorService FLUSH_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "trace-flush");
                t.setDaemon(true);
                return t;
            });

    public DefaultTraceCollector(TraceKind traceKind) {
        this.traceKind = traceKind;
    }

    protected TraceKind getTraceKind() {
        return traceKind;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void addTrace(String workspaceUri,
                         String contextId,
                         String content,
                         MessageType messageType) {
        TraceMessage message = new TraceMessage(
                getTraceKind(),
                workspaceUri,
                contextId,
                Instant.now(),
                content,
                messageType
        );
        traces.addLast(message);
        traceCount.incrementAndGet();
        while (traceCount.get() > MAX_TRACE_MESSAGES) {
            if (traces.pollFirst() != null) {
                traceCount.decrementAndGet();
            }
        }
        if (listeners.isEmpty()) {
            return;
        }
        pendingNotifications.add(message);
        if (flushScheduled.compareAndSet(false, true)) {
            FLUSH_EXECUTOR.schedule(this::flushNotifications, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushNotifications() {
        flushScheduled.set(false);
        List<TraceMessage> batch = new ArrayList<>();
        TraceMessage msg;
        while ((msg = pendingNotifications.poll()) != null) {
            batch.add(msg);
        }
        for (TraceMessage m : batch) {
            for (Consumer<TraceMessage> listener : listeners) {
                listener.accept(m);
            }
        }
    }

    @Override
    public void addTraceListener(Consumer<TraceMessage> listener) {
        listeners.add(listener);
    }

    @Override
    public void removeTraceListener(Consumer<TraceMessage> listener) {
        listeners.remove(listener);
    }

    @Override
    public List<TraceMessage> getTraces(int limit) {
        return filterTraces(t -> true, limit);
    }

    @Override
    public List<TraceMessage> getTraces(String workspaceUri,
                                        String contextId,
                                        int limit) {
        return filterTraces(
                t -> (t.workspaceUri() == null || t.workspaceUri().equals(workspaceUri))
                        && t.contextId().equals(contextId),
                limit
        );
    }

    @Override
    public List<TraceMessage> getTracesForSession(String serverId,
                                                  String sessionId,
                                                  int limit) {
        String sessionContextId = serverId + "#" + sessionId;
        return filterTraces(
                t -> t.contextId().equals(serverId) || t.contextId().equals(sessionContextId),
                limit
        );
    }

    protected List<TraceMessage> filterTraces(Predicate<TraceMessage> predicate, int limit) {
        var filtered = traces.stream().filter(predicate).toList();
        return filtered.subList(Math.max(0, filtered.size() - limit), filtered.size());
    }

    @Override
    public void clear() {
        traces.clear();
        traceCount.set(0);
    }
}
