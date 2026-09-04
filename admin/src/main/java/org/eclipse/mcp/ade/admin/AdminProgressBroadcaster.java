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

import org.eclipse.mcp.ade.admin.ws.ProgressInitWsMessage;
import org.eclipse.mcp.ade.admin.ws.ProgressUpdateWsMessage;
import org.eclipse.mcp.ade.progress.ProgressBroadcaster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class AdminProgressBroadcaster implements ProgressBroadcaster {

    private static final Logger LOG = Logger.getLogger(AdminProgressBroadcaster.class);

    private final ConcurrentHashMap<String, ActiveTask> activeTasks = new ConcurrentHashMap<>();

    @Inject
    Event<ProgressUpdateWsMessage> progressUpdateEvent;

    @Inject
    Event<ProgressInitWsMessage> progressInitEvent;

    @Override
    public void sendProgress(String taskId, String serverId, String title,
                             double progress, String message, String status,
                             String stepId, Double stepProgress) {
        ProgressUpdateWsMessage msg = new ProgressUpdateWsMessage(
            taskId,
            serverId,
            title,
            progress,
            message,
            status,
            stepId,
            stepProgress
        );

        if ("completed".equals(status) || "failed".equals(status)) {
            activeTasks.remove(taskId);
        } else {
            activeTasks.compute(taskId, (id, existing) -> {
                if (existing == null) {
                    return new ActiveTask(null, msg);
                }
                existing.lastUpdate = msg;
                return existing;
            });
        }

        progressUpdateEvent.fire(msg);
    }

    @Override
    public void sendProgress(String taskId, String serverId, String title,
                             double progress, String message, String status) {
        sendProgress(taskId, serverId, title, progress, message, status, null, null);
    }

    @Override
    public void taskRunning(String taskId, String serverId, String title,
                            double progress, String message,
                            String stepId, Double stepProgress) {
        sendProgress(taskId, serverId, title, progress, message, "running", stepId, stepProgress);
    }

    @Override
    public void taskRunning(String taskId, String serverId, String title, double progress, String message) {
        taskRunning(taskId, serverId, title, progress, message, null, null);
    }

    @Override
    public void taskCompleted(String taskId, String serverId, String title) {
        sendProgress(taskId, serverId, title, 1.0, null, "completed");
    }

    @Override
    public void taskCompleted(String taskId, String serverId, String title, String command) {
        ProgressUpdateWsMessage msg = new ProgressUpdateWsMessage(
            taskId, serverId, title, 1.0, null, "completed", null, null);
        msg.setCommand(command);
        activeTasks.remove(taskId);
        progressUpdateEvent.fire(msg);
    }

    @Override
    public void taskFailed(String taskId, String serverId, String title, String errorMessage) {
        sendProgress(taskId, serverId, title, 0.0, errorMessage, "failed");
    }

    @Override
    public void initTaskWithSteps(String taskId, String serverId, String title,
                                  List<StepInfo> steps, boolean cancellable) {
        List<ProgressInitWsMessage.StepInfo> wsSteps = steps.stream()
                .map(s -> new ProgressInitWsMessage.StepInfo(s.id(), s.weight(), s.title()))
                .toList();

        ProgressInitWsMessage msg = new ProgressInitWsMessage(
            taskId,
            serverId,
            title,
            wsSteps,
            cancellable
        );

        activeTasks.compute(taskId, (id, existing) -> {
            if (existing == null) {
                return new ActiveTask(msg, null);
            }
            existing.initMessage = msg;
            return existing;
        });

        progressInitEvent.fire(msg);
    }

    public Collection<ActiveTask> getActiveTasks() {
        return activeTasks.values();
    }

    public static class ActiveTask {
        volatile ProgressInitWsMessage initMessage;
        volatile ProgressUpdateWsMessage lastUpdate;

        ActiveTask(ProgressInitWsMessage initMessage, ProgressUpdateWsMessage lastUpdate) {
            this.initMessage = initMessage;
            this.lastUpdate = lastUpdate;
        }

        public ProgressInitWsMessage getInitMessage() {
            return initMessage;
        }

        public ProgressUpdateWsMessage getLastUpdate() {
            return lastUpdate;
        }
    }
}
