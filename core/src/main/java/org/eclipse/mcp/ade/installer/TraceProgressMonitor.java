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
package org.eclipse.mcp.ade.installer;

import org.eclipse.mcp.ade.progress.AbstractProgressMonitor;
import org.eclipse.mcp.ade.progress.ProgressBroadcaster;
import org.eclipse.mcp.ade.trace.TraceCollector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Progress monitor that sends traces via TraceCollector.
 * Optionally broadcasts progress to Admin UI via ProgressBroadcaster.
 */
public class TraceProgressMonitor extends AbstractProgressMonitor {
    private final TraceCollector traceCollector;
    private final AtomicBoolean canceled = new AtomicBoolean(false);
    private volatile String currentText = "";

    // Optional broadcasting to Admin UI
    private final ProgressBroadcaster broadcaster;
    private final String taskId;
    private final String serverId;
    private final String title;

    /**
     * Create trace progress monitor with default total of 100.
     */
    public TraceProgressMonitor(TraceCollector traceCollector) {
        this(traceCollector, 100.0, null, null, null, null);
    }

    /**
     * Create trace progress monitor with specific total.
     */
    public TraceProgressMonitor(TraceCollector traceCollector, double total) {
        this(traceCollector, total, null, null, null, null);
    }

    /**
     * Create trace progress monitor with broadcasting support.
     */
    public TraceProgressMonitor(TraceCollector traceCollector, double total,
                               ProgressBroadcaster broadcaster, String taskId, String serverId, String title) {
        super(total);
        this.traceCollector = traceCollector;
        this.broadcaster = broadcaster;
        this.taskId = taskId;
        this.serverId = serverId;
        this.title = title;
    }

    @Override
    public void initializeSteps() {
        if (broadcaster == null || taskId == null) {
            return;
        }
        List<ProgressBroadcaster.StepInfo> stepInfos = new ArrayList<>();
        for (var entry : getSteps().entrySet()) {
            var stepInfo = entry.getValue();
            stepInfos.add(new ProgressBroadcaster.StepInfo(
                    stepInfo.getId(),
                    stepInfo.getWeight(),
                    stepInfo.getId()
            ));
        }
        if (!stepInfos.isEmpty()) {
            broadcaster.initTaskWithSteps(taskId, serverId, title, stepInfos, true);
        }
        broadcaster.taskRunning(taskId, serverId, title, 0.0, "Starting...", null, null);
    }

    @Override
    public void reportProgress(double progress, String message) {
        double scaled = scaleToActiveStep(progress);
        setCurrent(scaled);
        this.currentText = message;

        // Broadcast to Admin UI if configured
        if (broadcaster != null && taskId != null) {
            double fraction = total > 0 ? scaled / total : 0.0;
            String stepId = getCurrentStepId();
            Double stepProgress = null;
            if (stepId != null) {
                double frac = getStepLocalFraction(scaled);
                if (frac >= 0) {
                    stepProgress = frac;
                }
            }
            broadcaster.taskRunning(taskId, serverId, title, fraction, message, stepId, stepProgress);
        }
    }

    @Override
    public void reportProgress(String message) {
        this.currentText = message;
        if (traceCollector != null && traceCollector.isEnabled() && serverId != null) {
            traceCollector.addTrace(serverId, message, TraceCollector.MessageType.INFO);
        }

        // Broadcast to Admin UI if configured
        if (broadcaster != null && taskId != null) {
            double fraction = total > 0 ? current / total : 0.0;
            String stepId = getCurrentStepId();
            Double stepProgress = null;
            if (stepId != null) {
                double frac = getStepLocalFraction(current);
                if (frac >= 0) {
                    stepProgress = frac;
                }
            }
            broadcaster.taskRunning(taskId, serverId, title, fraction, message, stepId, stepProgress);
        }
    }

    @Override
    public void setComplete() {
        setComplete(null);
    }

    public void setComplete(String command) {
        setCurrent(total);

        // Broadcast completion
        if (broadcaster != null && taskId != null) {
            broadcaster.taskCompleted(taskId, serverId, title, command);
        }
    }

    public void setFailed(String message) {
        if (broadcaster != null && taskId != null) {
            broadcaster.taskFailed(taskId, serverId, title, message);
        }
    }

    /**
     * Get the current progress fraction (0.0 to 1.0).
     * This is used by the UI to display a visual progress bar.
     */
    public double getFraction() {
        return total > 0 ? current / total : 0;
    }


    @Override
    public void cancel(String taskId) {
        // Admin can cancel ANY task (including installations)
        super.cancel(taskId);
        canceled.set(true);
    }

    @Override
    public boolean isCancelled() {
        // Check both parent's task-based cancellation and the legacy canceled flag
        return super.isCancelled() || canceled.get();
    }

    @Override
    public void checkCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Operation cancelled");
        }
    }

    @Override
    public <T> CompletableFuture<T> executeWithCancellation(CompletableFuture<T> future) {
        // Simple implementation: just return the future
        // Cancellation is handled via checkCancelled() calls
        return future;
    }

    @Override
    public boolean isSupported() {
        return traceCollector != null;
    }

    /**
     * Cancels the operation (legacy method for backward compatibility).
     */
    public void cancel() {
        canceled.set(true);
    }
}
