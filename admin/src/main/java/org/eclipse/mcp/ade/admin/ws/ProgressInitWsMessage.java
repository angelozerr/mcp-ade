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
package org.eclipse.mcp.ade.admin.ws;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

public class ProgressInitWsMessage extends WsMessage {

    private final String taskId;
    private final String serverId;
    private final String title;
    private final List<StepInfo> steps;
    private final boolean cancellable;

    public ProgressInitWsMessage(String taskId, String serverId, String title,
                                 List<StepInfo> steps, boolean cancellable) {
        super(WsMessageType.PROGRESS_INIT);
        this.taskId = taskId;
        this.serverId = serverId;
        this.title = title;
        this.steps = steps;
        this.cancellable = cancellable;
    }

    public String getTaskId() { return taskId; }
    public String getServerId() { return serverId; }
    public String getTitle() { return title; }
    public List<StepInfo> getSteps() { return steps; }
    public boolean isCancellable() { return cancellable; }

    @RegisterForReflection
    public record StepInfo(String id, double weight, String title) {}
}
