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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Abstract base class for ProgressMonitor implementations.
 * Provides common functionality for tracking total and current progress,
 * managing steps, and tracking tasks.
 */
public abstract class AbstractProgressMonitor implements ProgressMonitor {

    private static final AtomicLong TASK_COUNTER = new AtomicLong(0);

    protected final double total;
    protected volatile double current = 0;

    // Step management
    private final Map<String, StepInfo> steps = new LinkedHashMap<>();
    private double totalWeight = 0.0;
    private double completedWeight = 0.0;
    private String currentStepId;
    private volatile boolean insideStepReport;

    // Task tracking
    private final Set<String> cancelledTasks = ConcurrentHashMap.newKeySet();
    private String currentTaskId;

    /**
     * Create a progress monitor with default total of 100.
     */
    protected AbstractProgressMonitor() {
        this(100.0);
    }

    /**
     * Create a progress monitor with a specific total.
     *
     * @param total The total expected progress value
     */
    protected AbstractProgressMonitor(double total) {
        this.total = total;
    }

    /**
     * Get the total expected progress value.
     *
     * @return The total progress value
     */
    public double getTotal() {
        return total;
    }

    /**
     * Get the current progress value.
     *
     * @return The current progress value
     */
    public double getCurrent() {
        return current;
    }

    /**
     * Update the current progress value.
     *
     * @param current The new current progress value
     */
    protected void setCurrent(double current) {
        this.current = current;
    }

    /**
     * Scale a raw progress value (0-total) into the active step's range.
     * If no step is active or the call originates from a SubProgressMonitor
     * (already scaled), returns the value unchanged.
     */
    protected double scaleToActiveStep(double progress) {
        if (insideStepReport || currentStepId == null || steps.isEmpty()) {
            return progress;
        }
        StepInfo step = steps.get(currentStepId);
        if (step == null) {
            return progress;
        }
        double fraction = total > 0 ? progress / total : 0.0;
        return (step.startPercent + fraction * (step.endPercent - step.startPercent)) * total;
    }

    /**
     * Called by SubProgressMonitor to report already-scaled progress
     * without triggering step scaling again.
     * Includes a never-decrease guard to handle multiple sources reporting
     * to the same parent (e.g., parallel server installs sharing one step monitor).
     */
    void reportProgressFromStep(double progress, String message) {
        if (progress < current) {
            progress = current;
        }
        insideStepReport = true;
        try {
            reportProgress(progress, message);
        } finally {
            insideStepReport = false;
        }
    }

    @Override
    public ProgressMonitor addStep(String stepId, double weight) {
        if (weight <= 0) {
            throw new IllegalArgumentException("Step weight must be positive: " + weight);
        }

        // Store weight only - calculate ranges dynamically in beginStep()
        // This makes it safer: ranges are always recalculated based on current state
        steps.put(stepId, new StepInfo(stepId, weight, 0.0, 0.0));
        totalWeight += weight;
        return this;
    }

    @Override
    public ProgressMonitor beginStep(String stepId) {
        initializeSteps();
        StepInfo step = steps.get(stepId);
        if (step == null) {
            return this;
        }

        // Auto-complete previous step
        if (currentStepId != null && !currentStepId.equals(stepId)) {
            completeStep(currentStepId);
        }

        currentStepId = stepId;
        step.started = true;

        // Calculate ranges dynamically based on current completed weight
        double startPercent = totalWeight > 0 ? completedWeight / totalWeight : 0.0;
        double endPercent = totalWeight > 0 ? (completedWeight + step.weight) / totalWeight : 1.0;

        // Update step info with calculated ranges
        step.startPercent = startPercent;
        step.endPercent = endPercent;

        // Return sub-monitor scaled to this step's range
        return new SubProgressMonitor(this, stepId, startPercent, endPercent);
    }

    @Override
    public void completeStep(String stepId) {
        StepInfo step = steps.get(stepId);
        if (step != null && !step.completed) {
            step.completed = true;
            completedWeight += step.weight;
            double progressPercent = totalWeight > 0 ? completedWeight / totalWeight : 1.0;
            reportProgressFromStep(progressPercent * total, stepId);
        }
    }

    @Override
    public String startTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            taskId = "task-" + TASK_COUNTER.incrementAndGet();
        }
        currentTaskId = taskId;
        return taskId;
    }

    @Override
    public void endTask(String taskId) {
        if (taskId != null && taskId.equals(currentTaskId)) {
            currentTaskId = null;
        }
        // Remove from cancelled set to avoid memory leak
        cancelledTasks.remove(taskId);
    }

    @Override
    public void cancel(String taskId) {
        if (taskId != null) {
            cancelledTasks.add(taskId);
        }
    }

    @Override
    public boolean isCancelled() {
        return currentTaskId != null && cancelledTasks.contains(currentTaskId);
    }

    @Override
    public ProgressMonitor createSubMonitor(double start, double end) {
        if (start < 0 || start > 1 || end < 0 || end > 1 || start > end) {
            throw new IllegalArgumentException("Invalid range: start=" + start + ", end=" + end);
        }
        return new SubProgressMonitor(this, currentTaskId + "-sub", start, end);
    }

    /**
     * Get the current step ID, or null if no step is active.
     */
    public String getCurrentStepId() {
        return currentStepId;
    }

    /**
     * Compute the progress fraction (0.0-1.0) within the current step,
     * given the global scaled progress value.
     * Returns -1 if no step is active.
     */
    public double getStepLocalFraction(double scaledProgress) {
        if (currentStepId == null || steps.isEmpty()) {
            return -1;
        }
        StepInfo step = steps.get(currentStepId);
        if (step == null) {
            return -1;
        }
        double stepStart = step.startPercent * total;
        double stepRange = (step.endPercent - step.startPercent) * total;
        if (stepRange <= 0) {
            return 0;
        }
        double fraction = (scaledProgress - stepStart) / stepRange;
        return Math.max(0, Math.min(1, fraction));
    }

    /**
     * Get all declared steps.
     * @return Unmodifiable map of step ID to StepInfo
     */
    public Map<String, StepInfo> getSteps() {
        return java.util.Collections.unmodifiableMap(steps);
    }

    /**
     * Internal class to track step information.
     */
    public static class StepInfo {
        final String id;
        final double weight;
        double startPercent;  // Calculated dynamically in beginStep()
        double endPercent;    // Calculated dynamically in beginStep()
        boolean started;
        boolean completed;

        StepInfo(String id, double weight, double startPercent, double endPercent) {
            this.id = id;
            this.weight = weight;
            this.startPercent = startPercent;
            this.endPercent = endPercent;
        }

        public String getId() { return id; }
        public double getWeight() { return weight; }
        public boolean isStarted() { return started; }
        public boolean isCompleted() { return completed; }
    }
}
