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
package org.eclipse.mcp.ade.progress;

import org.jboss.logging.Logger;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A progress monitor that broadcasts updates to multiple listeners.
 *
 * Used when multiple operations are waiting for the same long-running task
 * (e.g., multiple LSP requests waiting for server installation to complete).
 *
 * Thread-safe: listeners can be added concurrently during execution.
 */
public class SharedProgressMonitor extends AbstractProgressMonitor {

    private static final Logger LOG = Logger.getLogger(SharedProgressMonitor.class);

    private final List<ProgressMonitor> listeners = new CopyOnWriteArrayList<>();

    /**
     * Add a listener that will receive all progress updates.
     *
     * @param listener The progress monitor to add as listener
     */
    public void addListener(ProgressMonitor listener) {
        if (listener != null && listener != ProgressMonitor.none()) {
            listeners.add(listener);
            LOG.debugf("Added listener to SharedProgressMonitor (total: %d)", listeners.size());
        }
    }

    /**
     * Remove a listener.
     *
     * @param listener The progress monitor to remove
     */
    public void removeListener(ProgressMonitor listener) {
        listeners.remove(listener);
    }

    @Override
    public void reportProgress(double progress, String message) {
        setCurrent(progress);

        // Normalize progress to a fraction (0.0-1.0), then scale to each listener's total.
        // This prevents scale mismatch when listeners have different totals
        // (e.g., SubProgressMonitor with total=1.0 receiving raw 0-100 values).
        double fraction = total > 0 ? progress / total : 0.0;

        // Broadcast to all listeners
        for (ProgressMonitor listener : listeners) {
            try {
                listener.reportProgress(fraction * listener.getTotal(), message);
            } catch (Exception e) {
                LOG.warnf(e, "Listener failed to receive progress update: %s", e.getMessage());
                // Continue with other listeners
            }
        }
    }

    @Override
    public void reportProgress(String message) {
        for (ProgressMonitor listener : listeners) {
            try {
                listener.reportProgress(message);
            } catch (Exception e) {
                LOG.warnf(e, "Listener failed to receive progress update: %s", e.getMessage());
            }
        }
    }

    @Override
    public void setComplete() {
        setCurrent(getTotal());

        for (ProgressMonitor listener : listeners) {
            try {
                listener.setComplete();
            } catch (Exception e) {
                LOG.warnf(e, "Listener failed to receive completion: %s", e.getMessage());
            }
        }
    }

    @Override
    public ProgressMonitor beginStep(String stepId) {
        for (ProgressMonitor listener : listeners) {
            try {
                listener.beginStep(stepId);
            } catch (Exception e) {
                LOG.debugf("Listener does not support step '%s': %s", stepId, e.getMessage());
            }
        }
        return this;
    }

    @Override
    public void completeStep(String stepId) {
        for (ProgressMonitor listener : listeners) {
            try {
                listener.completeStep(stepId);
            } catch (Exception e) {
                LOG.debugf("Listener does not support step '%s': %s", stepId, e.getMessage());
            }
        }
    }

    @Override
    public void checkCancelled() {
        // Use isCancelled() which requires ALL listeners to be cancelled.
        // One MCP client disconnecting must not kill a shared installation.
        if (isCancelled()) {
            throw new CancellationException("Operation cancelled");
        }
    }

    @Override
    public <T> CompletableFuture<T> executeWithCancellation(CompletableFuture<T> future) {
        // Apply cancellation from all delegates
        CompletableFuture<T> result = future;
        for (ProgressMonitor listener : listeners) {
            result = listener.executeWithCancellation(result);
        }
        return result;
    }

    @Override
    public boolean isSupported() {
        return listeners.stream().anyMatch(ProgressMonitor::isSupported);
    }

    @Override
    public boolean isCancelled() {
        // Only check our own task-based cancellation (Admin UI cancel button).
        // MCP client timeouts/disconnects must not kill shared installations.
        return super.isCancelled();
    }

    @Override
    public void cancel(String taskId) {
        super.cancel(taskId);

        // Propagate cancel to all listeners
        // Each listener decides whether to honor it
        for (ProgressMonitor listener : listeners) {
            try {
                listener.cancel(taskId);
            } catch (Exception e) {
                LOG.warnf(e, "Listener failed to receive cancel: %s", e.getMessage());
            }
        }
    }
}
