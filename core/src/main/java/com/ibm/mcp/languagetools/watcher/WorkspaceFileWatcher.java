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

import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Watches a workspace directory recursively for file changes.
 * Converts file system events to LSP {@link FileEvent}s and dispatches
 * them in batches to a callback.
 *
 * <p>Uses Java NIO {@link WatchService} with recursive directory registration.
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

    private WatchService watchService;
    private final Map<WatchKey, Path> watchKeyToDir = new ConcurrentHashMap<>();
    private Thread watchThread;
    private volatile boolean running;

    private final ScheduledExecutorService batchScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "file-watcher-batch");
                t.setDaemon(true);
                return t;
            });

    private final List<FileEvent> pendingBatch = new CopyOnWriteArrayList<>();
    private volatile ScheduledFuture<?> batchFuture;

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
        try {
            watchService = root.getFileSystem().newWatchService();
            registerRecursive(root);
            running = true;
            watchThread = new Thread(this::watchLoop, "workspace-file-watcher-" + root.getFileName());
            watchThread.setDaemon(true);
            watchThread.start();
            LOG.infof("Started file watcher for workspace: %s", root);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to start file watcher for: %s", root);
        }
    }

    public void stop() {
        running = false;
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                LOG.debugf("Error closing watch service: %s", e.getMessage());
            }
            watchService = null;
        }
        watchKeyToDir.clear();
        batchScheduler.shutdownNow();
        flushBatch();
        LOG.infof("Stopped file watcher for workspace: %s", root);
    }

    public boolean isRunning() {
        return running;
    }

    private void registerRecursive(Path dir) {
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    String dirName = d.getFileName() != null ? d.getFileName().toString() : "";
                    if (excludedDirs.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    registerDir(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warnf(e, "Failed to register directories under: %s", dir);
        }
    }

    private void registerDir(Path dir) {
        try {
            WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            watchKeyToDir.put(key, dir);
        } catch (IOException e) {
            LOG.debugf("Failed to register directory: %s", dir);
        }
    }

    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }
            if (key == null) {
                continue;
            }

            Path dir = watchKeyToDir.get(key);
            if (dir == null) {
                key.cancel();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path child = dir.resolve(pathEvent.context());

                if (kind == ENTRY_CREATE && Files.isDirectory(child)) {
                    String childName = child.getFileName().toString();
                    if (!excludedDirs.contains(childName)) {
                        registerRecursive(child);
                    }
                }

                FileChangeType changeType;
                if (kind == ENTRY_CREATE) {
                    changeType = FileChangeType.Created;
                } else if (kind == ENTRY_MODIFY) {
                    changeType = FileChangeType.Changed;
                } else if (kind == ENTRY_DELETE) {
                    changeType = FileChangeType.Deleted;
                } else {
                    continue;
                }

                String uri = child.toUri().toString();
                pendingBatch.add(new FileEvent(uri, changeType));
            }

            boolean valid = key.reset();
            if (!valid) {
                watchKeyToDir.remove(key);
            }

            scheduleBatchFlush();
        }
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

    private void flushBatch() {
        if (pendingBatch.isEmpty()) {
            return;
        }
        List<FileEvent> events = new ArrayList<>(pendingBatch);
        pendingBatch.clear();
        if (!events.isEmpty()) {
            LOG.infof("Dispatching %d file events: %s", events.size(),
                    events.stream().map(e -> e.getType() + " " + e.getUri()).toList());
            try {
                eventHandler.accept(events);
            } catch (Exception e) {
                LOG.warnf(e, "Error dispatching file events");
            }
        }
    }
}
