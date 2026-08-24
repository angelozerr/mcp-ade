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
package com.ibm.mcp.languagetools.watcher;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import io.methvin.watcher.visitor.FileTreeVisitor;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Watches a workspace directory recursively for file changes.
 * Converts file system events to LSP {@link FileEvent}s and dispatches
 * them in batches to a callback.
 *
 * <p>Uses {@link DirectoryWatcher} (io.methvin) for cross-platform
 * recursive watching with native OS APIs (ReadDirectoryChangesW on Windows,
 * FSEvents on macOS, inotify on Linux).
 * Events are batched (500ms window) to avoid flooding language servers.</p>
 */
public class WorkspaceFileWatcher {

    private static final Logger LOG = Logger.getLogger(WorkspaceFileWatcher.class);

    private static final long BATCH_DELAY_MS = 500;

    private static final Set<String> DEFAULT_EXCLUDED_DIRS = Set.of(
            ".git", "node_modules", "target", "build", ".idea", ".vscode",
            ".settings", "bin", ".metadata", ".classpath", ".project",
            "__pycache__", ".gradle", ".mvn"
    );

    private final Path root;
    private final Consumer<List<FileEvent>> eventHandler;
    private final Set<String> excludedDirs;
    private volatile Consumer<WorkspaceFileWatcher> statusChangeCallback;

    public enum Status {
        STOPPED, INITIALIZING, RUNNING, FAILED
    }

    private DirectoryWatcher watcher;
    private CompletableFuture<Void> watchFuture;
    private volatile Status status = Status.STOPPED;
    private volatile String failureReason;
    private volatile int scannedDirs;

    private final ExecutorService watchExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "file-watcher-loop");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private final ScheduledExecutorService batchScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "file-watcher-batch");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

    private final ConcurrentLinkedQueue<FileEvent> pendingBatch = new ConcurrentLinkedQueue<>();
    private volatile ScheduledFuture<?> batchFuture;
    private final ReentrantLock flushLock = new ReentrantLock();

    public WorkspaceFileWatcher(Path root, Consumer<List<FileEvent>> eventHandler) {
        this(root, eventHandler, null);
    }

    public WorkspaceFileWatcher(Path root, Consumer<List<FileEvent>> eventHandler, Set<String> additionalExcludes) {
        this.root = root;
        this.eventHandler = eventHandler;
        if (additionalExcludes != null && !additionalExcludes.isEmpty()) {
            Set<String> merged = new HashSet<>(DEFAULT_EXCLUDED_DIRS);
            merged.addAll(additionalExcludes);
            this.excludedDirs = Collections.unmodifiableSet(merged);
        } else {
            this.excludedDirs = DEFAULT_EXCLUDED_DIRS;
        }
    }

    public void start() {
        if (status == Status.RUNNING || status == Status.INITIALIZING) {
            return;
        }
        status = Status.INITIALIZING;
        failureReason = null;
        fireStatusChange();
        batchScheduler.submit(() -> {
            try {
                LOG.infof("Initializing file watcher for workspace: %s", root);
                scannedDirs = 0;
                long startTime = System.currentTimeMillis();
                FilteringFileTreeVisitor visitor = new FilteringFileTreeVisitor(excludedDirs, count -> {
                    scannedDirs = count;
                    if (status == Status.INITIALIZING) {
                        fireStatusChange();
                    }
                });
                DirectoryWatcher built = DirectoryWatcher.builder()
                        .path(root)
                        .listener(this::onEvent)
                        .fileHashing(false)
                        .fileTreeVisitor(visitor)
                        .build();
                long elapsed = System.currentTimeMillis() - startTime;
                synchronized (this) {
                    if (status != Status.INITIALIZING) {
                        try { built.close(); } catch (IOException ignored) {}
                        return;
                    }
                    watcher = built;
                }
                watchFuture = watcher.watchAsync(watchExecutor);
                status = Status.RUNNING;
                // Fire synthetic CREATE events for files created/modified during the scan.
                // OS watchers are registered incrementally (via onDirectory.call) during build(),
                // so events from already-scanned directories are buffered and delivered by watchAsync().
                // But files created in not-yet-scanned directories are invisible: no OS watcher
                // was registered when the file appeared, and when the scanner reaches the directory,
                // the file is part of the baseline (no CREATE event). The timestamp check in
                // visitFile catches these files.
                List<Path> recentFiles = visitor.getFilesCreatedDuringScan();
                if (!recentFiles.isEmpty()) {
                    LOG.infof("Found %d files created during scan, firing synthetic events", recentFiles.size());
                    for (Path file : recentFiles) {
                        String uri = file.toUri().toString();
                        pendingBatch.add(new FileEvent(uri, FileChangeType.Created));
                    }
                    scheduleBatchFlush();
                }
                LOG.infof("Started file watcher for workspace: %s (%d ms)", root, elapsed);
                fireStatusChange();
            } catch (Exception e) {
                if (status == Status.INITIALIZING) {
                    status = Status.FAILED;
                    failureReason = e.getMessage();
                    LOG.errorf(e, "Failed to start file watcher for: %s", root);
                    fireStatusChange();
                }
            }
        });
    }

    public void stop() {
        status = Status.STOPPED;
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                LOG.debugf("Error closing directory watcher: %s", e.getMessage());
            }
            watcher = null;
        }
        if (watchFuture != null) {
            watchFuture.cancel(true);
            watchFuture = null;
        }
        watchExecutor.shutdownNow();
        batchScheduler.shutdownNow();
        flushBatch();
        LOG.infof("Stopped file watcher for workspace: %s", root);
    }

    public boolean isRunning() {
        return status == Status.RUNNING;
    }

    public Status getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public int getScannedDirs() {
        return scannedDirs;
    }

    public void setStatusChangeCallback(Consumer<WorkspaceFileWatcher> callback) {
        this.statusChangeCallback = callback;
    }

    private void fireStatusChange() {
        var cb = statusChangeCallback;
        if (cb != null) {
            try {
                cb.accept(this);
            } catch (Exception e) {
                LOG.debugf("Error in status change callback: %s", e.getMessage());
            }
        }
    }

    private void onEvent(DirectoryChangeEvent event) {
        if (status != Status.RUNNING) {
            return;
        }

        Path path = event.path();
        if (path == null) {
            return;
        }

        // Filter excluded directories
        for (Path component : root.relativize(path)) {
            if (excludedDirs.contains(component.toString())) {
                return;
            }
        }

        FileChangeType changeType;
        switch (event.eventType()) {
            case CREATE:
                changeType = FileChangeType.Created;
                break;
            case MODIFY:
                changeType = FileChangeType.Changed;
                break;
            case DELETE:
                changeType = FileChangeType.Deleted;
                break;
            default:
                return;
        }

        String uri = path.toUri().toString();
        pendingBatch.add(new FileEvent(uri, changeType));
        scheduleBatchFlush();
    }

    private void scheduleBatchFlush() {
        if (batchFuture != null && !batchFuture.isDone()) {
            batchFuture.cancel(false);
        }
        try {
            batchFuture = batchScheduler.schedule(this::flushBatch, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.debugf("Batch flush rejected - scheduler shut down");
        }
    }

    public void flushNow() {
        if (watcher == null) {
            return;
        }
        if (batchFuture != null && !batchFuture.isDone()) {
            batchFuture.cancel(false);
        }
        flushBatch();
    }

    private void flushBatch() {
        if (pendingBatch.isEmpty()) {
            return;
        }
        if (!flushLock.tryLock()) {
            return;
        }
        try {
            List<FileEvent> events = new ArrayList<>();
            FileEvent event;
            while ((event = pendingBatch.poll()) != null) {
                events.add(event);
            }
            if (!events.isEmpty()) {
                LOG.infof("Dispatching %d file events: %s", events.size(),
                        events.stream().map(e -> e.getType() + " " + e.getUri()).toList());
                try {
                    eventHandler.accept(events);
                } catch (Exception e) {
                    LOG.warnf(e, "Error dispatching file events");
                }
            }
        } finally {
            flushLock.unlock();
        }
    }

    private static class FilteringFileTreeVisitor implements FileTreeVisitor {

        private static final long PROGRESS_INTERVAL_MS = 1000;

        private final Set<String> excludedDirs;
        private final java.util.function.IntConsumer progressCallback;
        private int dirCount;
        private long lastProgressTime;
        private long scanStartTime;
        private final List<Path> filesCreatedDuringScan = new ArrayList<>();

        FilteringFileTreeVisitor(Set<String> excludedDirs, java.util.function.IntConsumer progressCallback) {
            this.excludedDirs = excludedDirs;
            this.progressCallback = progressCallback;
        }

        @Override
        public void recursiveVisitFiles(Path root, Callback onDirectory, Callback onFile) throws IOException {
            dirCount = 0;
            scanStartTime = System.currentTimeMillis();
            lastProgressTime = scanStartTime;
            filesCreatedDuringScan.clear();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) {
                        return FileVisitResult.TERMINATE;
                    }
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (excludedDirs.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    onDirectory.call(dir);
                    dirCount++;
                    if (progressCallback != null) {
                        long now = System.currentTimeMillis();
                        if (now - lastProgressTime >= PROGRESS_INTERVAL_MS) {
                            lastProgressTime = now;
                            progressCallback.accept(dirCount);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    onFile.call(file);
                    if (attrs.lastModifiedTime().toMillis() >= scanStartTime) {
                        filesCreatedDuringScan.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (progressCallback != null) {
                progressCallback.accept(dirCount);
            }
        }

        List<Path> getFilesCreatedDuringScan() {
            return filesCreatedDuringScan;
        }
    }
}
