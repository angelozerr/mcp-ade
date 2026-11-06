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

import io.quarkiverse.mcp.server.tasks.TaskContext;

import java.util.concurrent.CompletableFuture;

/**
 * ProgressMonitor implementation that bridges to an MCP {@link TaskContext}.
 * Status messages are forwarded to {@link TaskContext#setStatusMessage(String)},
 * allowing the AI agent to poll task status and see installation/indexing progress.
 */
public class TaskProgressMonitor extends AbstractProgressMonitor {

    private final TaskContext taskContext;

    public TaskProgressMonitor(TaskContext taskContext) {
        super(100.0);
        this.taskContext = taskContext;
    }

    @Override
    public void reportProgress(double progress, String message) {
        double scaled = scaleToActiveStep(progress);
        setCurrent(scaled);
        if (message != null && !message.isEmpty()) {
            taskContext.setStatusMessage(message);
        }
    }

    @Override
    public void reportProgress(String message) {
        if (message != null && !message.isEmpty()) {
            taskContext.setStatusMessage(message);
        }
    }

    @Override
    public void setComplete() {
        setCurrent(total);
    }

    @Override
    public boolean isCancelled() {
        return super.isCancelled() || taskContext.cancellation().check().isRequested();
    }

    @Override
    public void checkCancelled() {
        if (super.isCancelled()) {
            throw new RuntimeException("Task cancelled");
        }
        if (taskContext.cancellation().check().isRequested()) {
            taskContext.cancellation().skipProcessingIfCancelled();
        }
    }

    @Override
    public <T> CompletableFuture<T> executeWithCancellation(CompletableFuture<T> future) {
        CompletableFuture<T> result = new CompletableFuture<>();

        taskContext.cancellation().onCancelled(reason -> {
            result.cancel(true);
            future.cancel(true);
        });

        future.whenComplete((value, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else if (isCancelled()) {
                result.completeExceptionally(new java.util.concurrent.CancellationException("Task cancelled"));
            } else if (!result.isDone()) {
                result.complete(value);
            }
        });
        return result;
    }

    @Override
    public boolean isSupported() {
        return true;
    }
}
