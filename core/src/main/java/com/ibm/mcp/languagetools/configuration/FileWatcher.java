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
package com.ibm.mcp.languagetools.configuration;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Watches one or more files for changes (create, modify, delete)
 * using a single WatchService and a single daemon thread.
 * Files can be registered before or after {@link #start()}.
 * Handles the case where a file's parent directory does not exist yet
 * by watching the grandparent directory for the parent's creation.
 */
public class FileWatcher {

    private static final Logger LOG = Logger.getLogger(FileWatcher.class);

    private record WatchedFile(Path file, Path parentDir, Path grandParentDir,
                               String targetFileName, String targetDirName, Runnable onChange) {
    }

    private final CopyOnWriteArrayList<WatchedFile> watchedFiles = new CopyOnWriteArrayList<>();
    private WatchService watchService;
    private ExecutorService executorService;
    private volatile boolean running = false;

    public FileWatcher(Path fileToWatch, Runnable onChange) {
        this();
        watchFile(fileToWatch, onChange);
    }

    public FileWatcher() {
    }

    /**
     * Register a file to watch. Can be called before or after {@link #start()}.
     */
    public FileWatcher watchFile(Path fileToWatch, Runnable onChange) {
        Path parentDir = fileToWatch.getParent();
        Path grandParentDir = parentDir != null ? parentDir.getParent() : null;
        String targetFileName = fileToWatch.getFileName().toString();
        String targetDirName = parentDir != null ? parentDir.getFileName().toString() : null;
        WatchedFile wf = new WatchedFile(fileToWatch, parentDir, grandParentDir,
                targetFileName, targetDirName, onChange);
        watchedFiles.add(wf);
        if (running && watchService != null) {
            try {
                registerWatch(wf);
                LOG.infof("Dynamically watching: %s", fileToWatch);
            } catch (ClosedWatchServiceException e) {
                LOG.debugf("Watch service closed, skipping registration for: %s", fileToWatch);
            } catch (IOException e) {
                LOG.warnf("Failed to register dynamic watch for: %s: %s", fileToWatch, e.getMessage());
            }
        }
        return this;
    }

    /**
     * Unregister a file from watching.
     */
    public void unwatchFile(Path fileToWatch) {
        watchedFiles.removeIf(wf -> wf.file.equals(fileToWatch));
        LOG.infof("Unwatched: %s", fileToWatch);
    }

    public void start() {
        if (running || watchedFiles.isEmpty()) {
            return;
        }

        try {
            running = true;
            watchService = FileSystems.getDefault().newWatchService();
            String threadName = "file-watcher-" + watchedFiles.get(0).targetFileName;
            executorService = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, threadName);
                t.setDaemon(true);
                return t;
            });

            registerAllWatches();

            executorService.submit(this::watchLoop);
            for (WatchedFile wf : watchedFiles) {
                LOG.infof("Started watching: %s", wf.file);
            }
        } catch (IOException e) {
            running = false;
            LOG.warnf(e, "Failed to start file watcher");
        }
    }

    public void stop() {
        running = false;

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                LOG.debugf("Error closing watch service: %s", e.getMessage());
            }
        }

        if (executorService != null) {
            executorService.shutdownNow();
        }

        for (WatchedFile wf : watchedFiles) {
            LOG.infof("Stopped watching: %s", wf.file);
        }
    }

    private void registerWatch(WatchedFile wf) throws IOException {
        if (wf.parentDir != null && Files.exists(wf.parentDir)) {
            wf.parentDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            LOG.debugf("Watching parent directory: %s", wf.parentDir);
        } else if (wf.grandParentDir != null && Files.exists(wf.grandParentDir)) {
            wf.grandParentDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            LOG.debugf("Watching grandparent directory: %s (waiting for %s)", wf.grandParentDir, wf.targetDirName);
        } else {
            LOG.debugf("Neither parent nor grandparent directory exists for: %s", wf.file);
        }
    }

    private void registerAllWatches() throws IOException {
        for (WatchedFile wf : watchedFiles) {
            try {
                registerWatch(wf);
            } catch (IOException e) {
                LOG.warnf("Failed to register watch for: %s: %s", wf.file, e.getMessage());
            }
        }
    }

    private void watchLoop() {
        try {
            while (running) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException | ClosedWatchServiceException e) {
                    break;
                }

                Path watchedDir = (Path) key.watchable();
                boolean needsReRegister = false;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    String eventFileName = ev.context().toString();

                    for (WatchedFile wf : watchedFiles) {
                        if (watchedDir.equals(wf.parentDir)) {
                            if (eventFileName.equals(wf.targetFileName)) {
                                fireOnChange(wf);
                            }
                        } else if (watchedDir.equals(wf.grandParentDir)) {
                            if (eventFileName.equals(wf.targetDirName)
                                    && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                                LOG.debugf("Target directory created: %s", wf.targetDirName);
                                needsReRegister = true;
                                if (Files.exists(wf.file)) {
                                    fireOnChange(wf);
                                }
                            }
                        }
                    }
                }

                boolean valid = key.reset();

                if (needsReRegister || !valid) {
                    try {
                        Thread.sleep(100);
                        registerAllWatches();
                    } catch (IOException e) {
                        LOG.debugf("Failed to re-register watches: %s", e.getMessage());
                        if (!valid) {
                            break;
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } catch (ClosedWatchServiceException e) {
            // Normal shutdown
        } catch (Exception e) {
            if (running) {
                LOG.errorf(e, "Error in file watch loop");
            }
        }
    }

    private void fireOnChange(WatchedFile wf) {
        LOG.infof("File change detected: %s", wf.file);
        try {
            wf.onChange.run();
        } catch (Exception e) {
            LOG.errorf(e, "Error in file change callback for: %s", wf.file);
        }
    }
}
