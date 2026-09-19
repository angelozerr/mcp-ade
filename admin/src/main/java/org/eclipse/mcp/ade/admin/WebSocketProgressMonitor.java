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
package org.eclipse.mcp.ade.admin;

import org.eclipse.mcp.ade.progress.AbstractProgressMonitor;
import org.eclipse.mcp.ade.progress.ProgressBroadcaster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Progress monitor that broadcasts progress updates via WebSocket to Admin UI.
 * Supports cancellation from the Admin UI via a cancellation signal that
 * propagates to all wrapped CompletableFutures.
 */
public class WebSocketProgressMonitor extends AbstractProgressMonitor {

    private final ProgressBroadcaster broadcaster;
    private final AdminProgressBroadcaster adminBroadcaster;
    private final String taskId;
    private final String serverId;
    private final String title;
    private boolean stepsInitialized = false;
    private final CompletableFuture<Void> cancellationSignal = new CompletableFuture<>();

    public WebSocketProgressMonitor(
            ProgressBroadcaster broadcaster,
            AdminProgressBroadcaster adminBroadcaster,
            String taskId,
            String serverId,
            String title) {
        super(100.0);
        this.broadcaster = broadcaster;
        this.adminBroadcaster = adminBroadcaster;
        this.taskId = taskId;
        this.serverId = serverId;
        this.title = title;

        if (adminBroadcaster != null) {
            adminBroadcaster.registerCancellableMonitor(taskId, this);
        }
    }

    @Override
    public void initializeSteps() {
        if (stepsInitialized || broadcaster == null) {
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

        broadcaster.initTaskWithSteps(taskId, serverId, title, stepInfos, true);
        stepsInitialized = true;
    }

    private void ensureInitialized() {
        initializeSteps();
    }

    @Override
    public void reportProgress(double progress, String message) {
        ensureInitialized();
        double scaled = scaleToActiveStep(progress);
        setCurrent(scaled);
        if (broadcaster != null) {
            String stepId = getCurrentStepId();
            Double stepProgress = null;
            if (stepId != null) {
                double frac = getStepLocalFraction(scaled);
                if (frac >= 0) {
                    stepProgress = frac;
                }
            }
            broadcaster.taskRunning(taskId, serverId, title, scaled / total, message, stepId, stepProgress);
        }
    }

    @Override
    public void reportProgress(String message) {
        ensureInitialized();
        if (broadcaster != null) {
            String stepId = getCurrentStepId();
            Double stepProgress = null;
            if (stepId != null) {
                double frac = getStepLocalFraction(getCurrent());
                if (frac >= 0) {
                    stepProgress = frac;
                }
            }
            broadcaster.taskRunning(taskId, serverId, title, getCurrent() / total, message, stepId, stepProgress);
        }
    }

    @Override
    public void setComplete() {
        setCurrent(total);
        if (broadcaster != null) {
            broadcaster.taskCompleted(taskId, serverId, title);
        }
        if (adminBroadcaster != null) {
            adminBroadcaster.unregisterCancellableMonitor(taskId);
        }
    }

    @Override
    public boolean isSupported() {
        return broadcaster != null;
    }

    @Override
    public void cancel(String taskId) {
        super.cancel(taskId);
        if (this.taskId.equals(taskId)) {
            cancellationSignal.complete(null);
        }
    }

    @Override
    public boolean isCancelled() {
        return cancellationSignal.isDone() || super.isCancelled();
    }

    @Override
    public void checkCancelled() {
        if (isCancelled()) {
            throw new CancellationException("Task cancelled from admin");
        }
    }

    @Override
    public <T> CompletableFuture<T> executeWithCancellation(CompletableFuture<T> future) {
        CompletableFuture<T> result = new CompletableFuture<>();

        cancellationSignal.thenRun(() -> {
            result.cancel(true);
            future.cancel(true);
        });

        future.whenComplete((value, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else if (!result.isDone()) {
                result.complete(value);
            }
        });
        return result;
    }
}
