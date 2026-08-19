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

    private DirectoryWatcher watcher;
    private CompletableFuture<Void> watchFuture;
    private volatile boolean running;

    private final ScheduledExecutorService batchScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "file-watcher-batch");
                t.setDaemon(true);
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
        if (running) {
            return;
        }
        running = true;
        batchScheduler.submit(() -> {
            try {
                LOG.infof("Initializing file watcher for workspace: %s", root);
                long startTime = System.currentTimeMillis();
                DirectoryWatcher built = DirectoryWatcher.builder()
                        .path(root)
                        .listener(this::onEvent)
                        .fileTreeVisitor(new FilteringFileTreeVisitor(excludedDirs))
                        .build();
                long elapsed = System.currentTimeMillis() - startTime;
                synchronized (this) {
                    if (!running) {
                        try { built.close(); } catch (IOException ignored) {}
                        return;
                    }
                    watcher = built;
                }
                watchFuture = watcher.watchAsync();
                LOG.infof("Started file watcher for workspace: %s (%d ms)", root, elapsed);
            } catch (IOException e) {
                LOG.errorf(e, "Failed to start file watcher for: %s", root);
            }
        });
    }

    public void stop() {
        running = false;
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
        batchScheduler.shutdownNow();
        flushBatch();
        LOG.infof("Stopped file watcher for workspace: %s", root);
    }

    public boolean isRunning() {
        return running;
    }

    private void onEvent(DirectoryChangeEvent event) {
        if (!running) {
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
            // Scheduler shut down
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

        private final Set<String> excludedDirs;

        FilteringFileTreeVisitor(Set<String> excludedDirs) {
            this.excludedDirs = excludedDirs;
        }

        @Override
        public void recursiveVisitFiles(Path root, Callback onDirectory, Callback onFile) throws IOException {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (excludedDirs.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    onDirectory.call(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    onFile.call(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
