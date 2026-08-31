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
package org.eclipse.mcp.ade.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileWatcherTest {

    private static final long WAIT_TIMEOUT_MS = 5000;

    @Test
    void fileCreated(@TempDir Path root) throws Exception {
        Path dir = root.resolve(".vscode");
        Files.createDirectories(dir);
        Path file = dir.resolve("settings.json");

        var latch = new CountDownLatch(1);
        var watcher = new FileWatcher(file, latch::countDown);
        try {
            watcher.start();
            Thread.sleep(200);
            Files.writeString(file, "{}");
            assertTrue(latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "Callback should be called when file is created");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void fileModified(@TempDir Path root) throws Exception {
        Path dir = root.resolve(".vscode");
        Files.createDirectories(dir);
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{}");

        var latch = new CountDownLatch(1);
        var watcher = new FileWatcher(file, latch::countDown);
        try {
            watcher.start();
            Thread.sleep(200);
            Files.writeString(file, "{\"key\": \"value\"}");
            assertTrue(latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "Callback should be called when file is modified");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void fileDeleted(@TempDir Path root) throws Exception {
        Path dir = root.resolve(".vscode");
        Files.createDirectories(dir);
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{}");

        var latch = new CountDownLatch(1);
        var watcher = new FileWatcher(file, latch::countDown);
        try {
            watcher.start();
            Thread.sleep(200);
            Files.delete(file);
            assertTrue(latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "Callback should be called when file is deleted");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void fileDeletedThenRecreated(@TempDir Path root) throws Exception {
        Path dir = root.resolve(".vscode");
        Files.createDirectories(dir);
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{}");

        var callCount = new AtomicInteger(0);
        var secondCall = new CountDownLatch(2);
        var watcher = new FileWatcher(file, () -> {
            callCount.incrementAndGet();
            secondCall.countDown();
        });
        try {
            watcher.start();
            Thread.sleep(200);
            Files.delete(file);
            Thread.sleep(1500);
            Files.writeString(file, "{\"new\": true}");
            assertTrue(secondCall.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "Callback should be called at least twice (delete + create)");
            assertTrue(callCount.get() >= 2);
        } finally {
            watcher.stop();
        }
    }

    @Test
    void directoryCreatedThenFile(@TempDir Path root) throws Exception {
        Path dir = root.resolve(".bob");
        Path file = dir.resolve("settings.json");

        var latch = new CountDownLatch(1);
        var watcher = new FileWatcher(file, latch::countDown);
        try {
            watcher.start();
            Thread.sleep(200);
            Files.createDirectories(dir);
            Thread.sleep(500);
            Files.writeString(file, "{}");
            assertTrue(latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "Callback should be called when directory is created and then file is written");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void directoryDeletedThenRecreated(@TempDir Path root) throws Exception {
        Path dir = root.resolve(".bob");
        Files.createDirectories(dir);
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{}");

        var callCount = new AtomicInteger(0);
        var latch = new CountDownLatch(2);
        var watcher = new FileWatcher(file, () -> {
            callCount.incrementAndGet();
            latch.countDown();
        });
        try {
            watcher.start();
            Thread.sleep(200);
            Files.delete(file);
            Files.delete(dir);
            Thread.sleep(500);
            Files.createDirectories(dir);
            Thread.sleep(500);
            Files.writeString(file, "{\"recreated\": true}");
            assertTrue(latch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                    "Callback should be called after directory is deleted and recreated with file");
            assertTrue(callCount.get() >= 2);
        } finally {
            watcher.stop();
        }
    }
}
