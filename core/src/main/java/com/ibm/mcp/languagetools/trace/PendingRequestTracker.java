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
package com.ibm.mcp.languagetools.trace;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Tracks pending request/response pairs and cleans up orphaned entries.
 *
 * @param <T> the request metadata type
 */
public class PendingRequestTracker<T> {

    private static final long DEFAULT_TIMEOUT_MS = 300_000;

    private final Map<String, T> requests = new ConcurrentHashMap<>();
    private final Function<T, Instant> timestampExtractor;
    private final long timeoutMs;

    public PendingRequestTracker(Function<T, Instant> timestampExtractor) {
        this(timestampExtractor, DEFAULT_TIMEOUT_MS);
    }

    public PendingRequestTracker(Function<T, Instant> timestampExtractor, long timeoutMs) {
        this.timestampExtractor = timestampExtractor;
        this.timeoutMs = timeoutMs;
    }

    public void track(String key, T metadata, Instant now) {
        cleanup(now);
        requests.put(key, metadata);
    }

    public T resolve(String key) {
        return requests.remove(key);
    }

    private void cleanup(Instant now) {
        long nowMs = now.toEpochMilli();
        requests.entrySet().removeIf(entry ->
                nowMs - timestampExtractor.apply(entry.getValue()).toEpochMilli() > timeoutMs);
    }
}
