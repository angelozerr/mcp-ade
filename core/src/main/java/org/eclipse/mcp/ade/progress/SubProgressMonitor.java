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

import java.util.concurrent.CompletableFuture;

/**
 * A progress monitor that scales progress to a portion of a parent monitor.
 *
 * Example: If start=0.4 and end=0.7, then:
 * - reportProgress(0.0, msg) → parent receives 0.4
 * - reportProgress(0.5, msg) → parent receives 0.55
 * - reportProgress(1.0, msg) → parent receives 0.7
 */
public class SubProgressMonitor implements ProgressMonitor {

    private final ProgressMonitor parent;
    private final String taskId;
    private final double startPercent;
    private final double endPercent;
    // Track max to prevent regression when multiple sources share this monitor
    private volatile double maxScaled;

    public SubProgressMonitor(ProgressMonitor parent, String taskId, double startPercent, double endPercent) {
        this.parent = parent;
        this.taskId = taskId;
        this.startPercent = startPercent;
        this.endPercent = endPercent;
        this.maxScaled = startPercent;
    }

    @Override
    public void reportProgress(double progress, String message) {
        // Scale progress (0.0-1.0) to parent's range and total
        double scaled = startPercent + (progress * (endPercent - startPercent));
        if (scaled > maxScaled) {
            maxScaled = scaled;
        }
        // Use maxScaled to prevent regression (e.g., when multiple servers
        // share the same install monitor and one completes before the other)
        double scaledValue = maxScaled * parent.getTotal();
        if (parent instanceof AbstractProgressMonitor apm) {
            apm.reportProgressFromStep(scaledValue, message);
        } else {
            parent.reportProgress(scaledValue, message);
        }
    }

    @Override
    public void reportProgress(String message) {
        parent.reportProgress(message);
    }

    @Override
    public void setComplete() {
        double scaled = endPercent;
        if (scaled > maxScaled) {
            maxScaled = scaled;
        }
        double scaledValue = maxScaled * parent.getTotal();
        if (parent instanceof AbstractProgressMonitor apm) {
            apm.reportProgressFromStep(scaledValue, "Completed");
        } else {
            parent.reportProgress(scaledValue, "Completed");
        }
    }

    @Override
    public double getTotal() {
        return 1.0; // Sub-monitors always work on 0.0-1.0 scale
    }

    @Override
    public boolean isCancelled() {
        return parent.isCancelled();
    }

    @Override
    public void checkCancelled() {
        parent.checkCancelled();
    }

    @Override
    public <T> CompletableFuture<T> executeWithCancellation(CompletableFuture<T> future) {
        return parent.executeWithCancellation(future);
    }

    @Override
    public boolean isSupported() {
        return parent.isSupported();
    }

    @Override
    public ProgressMonitor addStep(String stepId, double weight) {
        // No-op: sub-monitors don't own steps, the parent does
        return this;
    }

    @Override
    public ProgressMonitor beginStep(String stepId) {
        // No-op: return this so callers can use a single code path
        // regardless of whether the monitor has steps or not
        return this;
    }

    @Override
    public void completeStep(String stepId) {
        // No-op
    }

    @Override
    public String startTask(String taskId) {
        return parent.startTask(taskId);
    }

    @Override
    public void endTask(String taskId) {
        parent.endTask(taskId);
    }

    @Override
    public void cancel(String taskId) {
        parent.cancel(taskId);
    }

    @Override
    public ProgressMonitor createSubMonitor(double start, double end) {
        // Create nested sub-monitor by scaling ranges
        double nestedStart = startPercent + (start * (endPercent - startPercent));
        double nestedEnd = startPercent + (end * (endPercent - startPercent));
        return new SubProgressMonitor(parent, taskId + "-sub", nestedStart, nestedEnd);
    }
}
