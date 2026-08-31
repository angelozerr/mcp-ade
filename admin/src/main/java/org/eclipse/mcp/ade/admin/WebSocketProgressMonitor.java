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

/**
 * Progress monitor that broadcasts progress updates via WebSocket to Admin UI.
 * Does NOT send trace messages (use TraceProgressMonitor for that).
 */
public class WebSocketProgressMonitor extends AbstractProgressMonitor {

    private final ProgressBroadcaster broadcaster;
    private final String taskId;
    private final String serverId;
    private final String title;
    private boolean stepsInitialized = false;

    public WebSocketProgressMonitor(
            ProgressBroadcaster broadcaster,
            String taskId,
            String serverId,
            String title) {
        super(100.0);
        this.broadcaster = broadcaster;
        this.taskId = taskId;
        this.serverId = serverId;
        this.title = title;
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

        if (!stepInfos.isEmpty()) {
            broadcaster.initTaskWithSteps(taskId, serverId, title, stepInfos, false);
            stepsInitialized = true;
        }
    }

    @Override
    public void reportProgress(double progress, String message) {
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
    }

    @Override
    public boolean isSupported() {
        return broadcaster != null;
    }

    @Override
    public void checkCancelled() {
        // No cancellation support in WebSocket monitor
    }

    @Override
    public <T> java.util.concurrent.CompletableFuture<T> executeWithCancellation(
            java.util.concurrent.CompletableFuture<T> future) {
        // No cancellation support in WebSocket monitor - just pass through
        return future;
    }
}
