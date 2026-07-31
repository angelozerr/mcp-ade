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
package com.ibm.mcp.languagetools.progress;

import java.util.concurrent.CompletableFuture;

/**
 * Progress monitoring and cancellation support for long-running operations.
 * Inspired by IntelliJ's ProgressIndicator API.
 *
 * This interface is independent of any specific protocol (MCP, LSP, etc.)
 * and can be adapted to different progress/cancellation mechanisms.
 */
public interface ProgressMonitor {

    /**
     * Report progress with a specific current value.
     * The total is defined at construction time.
     *
     * @param progress Current progress value
     * @param message Descriptive message for the user
     */
    void reportProgress(double progress, String message);

    /**
     * Report progress with an indeterminate amount (message only).
     *
     * @param message Descriptive message for the user
     */
    void reportProgress(String message);

    /**
     * Mark the operation as complete.
     */
    void setComplete();

    /**
     * Get the total expected progress value.
     *
     * @return The total progress value
     */
    double getTotal();

    /**
     * Check if the operation has been cancelled.
     *
     * @return true if cancelled, false otherwise
     */
    boolean isCancelled();

    /**
     * Check if cancelled and throw some cancel exception if so.
     */
    void checkCancelled();

    /**
     * Wrap a CompletableFuture to propagate cancellation.
     * If the operation is cancelled, the returned future will complete exceptionally
     * with CancellationException.
     *
     * @param future The future to wrap
     * @param <T> The future result type
     * @return A future that completes exceptionally if cancelled
     */
    <T> CompletableFuture<T> executeWithCancellation(CompletableFuture<T> future);

    /**
     * Report a trace-only message (visible in traces but does not update status).
     *
     * @param message Trace message
     */
    default void reportTrace(String message) {
    }

    /**
     * Check if progress reporting is supported/available.
     *
     * @return true if progress can be reported, false otherwise
     */
    boolean isSupported();

    /**
     * Declare a step with a relative weight.
     * All steps should be declared upfront before execution begins.
     * Weights are relative and don't need to sum to 1.0.
     *
     * @param stepId Unique identifier for this step
     * @param weight Relative weight of this step (e.g., 0.4 for 40%)
     */
    ProgressMonitor addStep(String stepId, double weight);

    default ProgressMonitor addStep(ProgressStep step, double weight) {
        return addStep(step.label(), weight);
    }

    /**
     * Begin executing a step. Returns a sub-monitor scaled to this step's range.
     * Must be called in the same order as addStep().
     * Auto-completes the previous step if still active.
     *
     * @param stepId Step identifier (must have been declared via addStep)
     * @return A sub-monitor that scales progress to this step's range
     */
    ProgressMonitor beginStep(String stepId);

    default ProgressMonitor beginStep(ProgressStep step) {
        return beginStep(step.label());
    }

    /**
     * Mark a step as complete.
     * Optional - beginStep auto-completes the previous step.
     *
     * @param stepId Step identifier
     */
    void completeStep(String stepId);

    default void completeStep(ProgressStep step) {
        completeStep(step.label());
    }

    /**
     * Start a tracked task/operation.
     *
     * @param taskId Unique identifier for this task
     * @return The task ID (same as input)
     */
    String startTask(String taskId);

    /**
     * End a tracked task.
     *
     * @param taskId Task identifier
     */
    void endTask(String taskId);

    /**
     * Cancel a specific task by ID.
     * Implementations decide whether to honor this cancellation.
     *
     * @param taskId Task identifier
     */
    void cancel(String taskId);

    /**
     * Create a sub-monitor that scales progress to a portion of the parent.
     *
     * @param start Start percentage (0.0 to 1.0)
     * @param end End percentage (0.0 to 1.0)
     * @return A sub-monitor that scales progress to the parent's range
     */
    ProgressMonitor createSubMonitor(double start, double end);

    default void initializeSteps() {
    }

    /**
     * Create a no-op progress monitor that does nothing.
     * Useful for operations that don't need progress tracking.
     *
     * @return A no-op progress monitor instance
     */
    static ProgressMonitor none() {
        return NoOpProgressMonitor.INSTANCE;
    }

}
