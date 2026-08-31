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

import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.ProgressTracker;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

/**
 * ProgressMonitor implementation that adapts Quarkus MCP Server's Progress and Cancellation APIs.
 * Uses ProgressTracker for efficient progress reporting with automatic total management.
 */
public class McpProgressMonitor extends AbstractProgressMonitor {

    private final Progress progress;
    private final Cancellation cancellation;
    private final ProgressTracker tracker;
    private volatile String lastMessage = "";

    /**
     * Create MCP progress monitor with default total of 100.
     */
    public McpProgressMonitor(Progress progress, Cancellation cancellation) {
        this(progress, cancellation, 100.0);
    }

    /**
     * Create MCP progress monitor with specific total.
     */
    public McpProgressMonitor(Progress progress, Cancellation cancellation, double total) {
        super(total);
        this.progress = progress;
        this.cancellation = cancellation;

        // Create ProgressTracker if progress token is available
        if (progress != null && progress.token().isPresent()) {
            this.tracker = progress.trackerBuilder()
                    .setTotal(total)
                    .setMessageBuilder(current -> lastMessage.isEmpty() ? "Progress: " + formatProgress(current) : lastMessage)
                    .build();
        } else {
            this.tracker = null;
        }
    }

    @Override
    public void reportProgress(double progress, String message) {
        double scaled = scaleToActiveStep(progress);
        setCurrent(scaled);
        this.lastMessage = message;

        if (tracker != null) {
            // Calculate delta from current tracker progress
            BigDecimal currentProgress = tracker.progress();
            BigDecimal targetProgress = BigDecimal.valueOf(scaled);
            BigDecimal delta = targetProgress.subtract(currentProgress);

            if (delta.compareTo(BigDecimal.ZERO) > 0) {
                tracker.advanceAndForget(delta);
            }
        }
    }

    @Override
    public void reportProgress(String message) {
        this.lastMessage = message;
        if (progress != null && progress.token().isPresent()) {
            progress.notificationBuilder()
                    .setMessage(message)
                    .setProgress(0)
                    .build()
                    .sendAndForget();
        }
    }

    @Override
    public void setComplete() {
        setCurrent(total);
        if (tracker != null) {
            // Advance to total to mark as complete
            BigDecimal remaining = BigDecimal.valueOf(total).subtract(tracker.progress());
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                tracker.advanceAndForget(remaining);
            }
        }
    }

    @Override
    public void cancel(String taskId) {
        // MCP client (AI) CANNOT cancel installation tasks
        // These run in the background and will be reused by other operations
        if (taskId != null && taskId.startsWith("install-")) {
            // Silently ignore - installation continues
            return;
        }

        // MCP client CAN cancel other operations (execute, start, etc.)
        super.cancel(taskId);
    }

    @Override
    public boolean isCancelled() {
        // Check both parent's task-based cancellation and MCP cancellation
        return super.isCancelled() || (cancellation != null && cancellation.check().isRequested());
    }

    @Override
    public void checkCancelled() {
        // Check parent's task-based cancellation first
        if (super.isCancelled()) {
            throw new RuntimeException("Task cancelled");
        }

        // Only check MCP cancellation if it was actually requested
        if (cancellation != null && cancellation.check().isRequested()) {
            cancellation.skipProcessingIfCancelled();
        }
    }

    @Override
    public <T> CompletableFuture<T> executeWithCancellation(CompletableFuture<T> future) {
        if (cancellation == null) {
            return future;
        }

        CompletableFuture<T> result = new CompletableFuture<>();

        // Listen for cancellation
        cancellation.onCancelled(reason -> result.cancel(true));

        // Forward the original future result or exception
        future.whenComplete((value, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else if (!result.isDone()) {
                result.complete(value);
            }
        });
        return result;
    }

    @Override
    public boolean isSupported() {
        return progress != null && progress.token().isPresent();
    }

    /**
     * Format progress value for display.
     */
    private String formatProgress(BigDecimal current) {
        if (total > 0) {
            double percent = (current.doubleValue() / total) * 100;
            return String.format("%.0f%%", percent);
        }
        return String.format("%.0f", current.doubleValue());
    }
}

