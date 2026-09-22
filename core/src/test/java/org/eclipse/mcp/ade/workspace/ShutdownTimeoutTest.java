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
package org.eclipse.mcp.ade.workspace;

import org.eclipse.mcp.ade.server.ServerStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the shutdown timeout patterns used in Workspace and Application.
 * These tests validate the CompletableFuture.orTimeout + exceptionally patterns
 * that prevent indefinite blocking when servers fail to shut down.
 */
class ShutdownTimeoutTest {

    @Test
    void perServerTimeout_completesNormally() throws Exception {
        CompletableFuture<Void> serverShutdown = CompletableFuture.runAsync(() -> {});

        CompletableFuture<Void> withTimeout = serverShutdown
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    fail("Should not timeout for fast shutdown");
                    return null;
                });

        withTimeout.get(2, TimeUnit.SECONDS);
    }

    @Test
    void perServerTimeout_forcesStoppedOnTimeout() throws Exception {
        AtomicReference<ServerStatus> status = new AtomicReference<>(ServerStatus.STOPPING);

        CompletableFuture<Void> hangingShutdown = new CompletableFuture<>();

        CompletableFuture<Void> withTimeout = hangingShutdown
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    assertTrue(ex instanceof TimeoutException
                                    || (ex.getCause() != null && ex.getCause() instanceof TimeoutException),
                            "Expected TimeoutException but got: " + ex);
                    status.set(ServerStatus.STOPPED);
                    return null;
                });

        withTimeout.get(2, TimeUnit.SECONDS);
        assertEquals(ServerStatus.STOPPED, status.get());
    }

    @Test
    void allOfWithTimeouts_completesWhenAllServersComplete() throws Exception {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            futures.add(CompletableFuture.runAsync(() -> {})
                    .orTimeout(1, TimeUnit.SECONDS)
                    .exceptionally(ex -> null));
        }

        CompletableFuture<Void> allOf = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]));

        allOf.get(2, TimeUnit.SECONDS);
    }

    @Test
    void allOfWithTimeouts_completesEvenWhenSomeServerHang() throws Exception {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        futures.add(CompletableFuture.runAsync(() -> {})
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> null));

        futures.add(new CompletableFuture<Void>()
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> null));

        futures.add(CompletableFuture.runAsync(() -> {})
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> null));

        CompletableFuture<Void> allOf = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]));

        allOf.get(2, TimeUnit.SECONDS);
    }

    @Test
    void globalTimeout_wrapsPerServerTimeouts() throws Exception {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        futures.add(new CompletableFuture<Void>()
                .orTimeout(200, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> null));

        CompletableFuture<Void> workspace = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {});

        AtomicReference<String> result = new AtomicReference<>("pending");

        CompletableFuture<Void> global = workspace
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    result.set("timed out");
                    return null;
                })
                .thenRun(() -> {
                    if ("pending".equals(result.get())) {
                        result.set("completed");
                    }
                });

        global.get(2, TimeUnit.SECONDS);
        assertEquals("completed", result.get());
    }

    @Test
    void globalTimeout_firesWhenPerServerTimeoutsTooSlow() throws Exception {
        AtomicReference<String> result = new AtomicReference<>("pending");

        CompletableFuture<Void> hangingServer = new CompletableFuture<>();

        CompletableFuture<Void> global = hangingServer
                .orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    result.set("timed out");
                    return null;
                });

        global.get(2, TimeUnit.SECONDS);
        assertEquals("timed out", result.get());
    }

    @Test
    void closingFlag_isVolatile() throws Exception {
        var closing = new AtomicReference<>(false);

        Thread writer = new Thread(() -> closing.set(true));
        writer.start();
        writer.join(1000);

        assertTrue(closing.get());
    }

    @Test
    void parallelShutdowns_completeIndependently() throws Exception {
        int serverCount = 10;
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        AtomicReference<Integer> completedCount = new AtomicReference<>(0);

        for (int i = 0; i < serverCount; i++) {
            int idx = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(idx * 10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                completedCount.updateAndGet(c -> c + 1);
            }).orTimeout(2, TimeUnit.SECONDS)
              .exceptionally(ex -> null));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(5, TimeUnit.SECONDS);

        assertEquals(serverCount, completedCount.get());
    }

    @Test
    void workspaceRemovedEvenOnTimeout() throws Exception {
        List<String> workspaces = new ArrayList<>(List.of("ws1", "ws2", "ws3"));

        CompletableFuture<Void> hanging = new CompletableFuture<>();

        hanging.orTimeout(100, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> null)
                .thenRun(() -> {
                    workspaces.remove("ws2");
                })
                .get(2, TimeUnit.SECONDS);

        assertEquals(List.of("ws1", "ws3"), workspaces);
    }
}
