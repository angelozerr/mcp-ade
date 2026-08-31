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

import java.util.List;

public interface ProgressBroadcaster {

    void sendProgress(String taskId, String serverId, String title,
                      double progress, String message, String status,
                      String stepId, Double stepProgress);

    void sendProgress(String taskId, String serverId, String title,
                      double progress, String message, String status);

    void taskRunning(String taskId, String serverId, String title,
                     double progress, String message, String stepId, Double stepProgress);

    void taskRunning(String taskId, String serverId, String title, double progress, String message);

    void taskCompleted(String taskId, String serverId, String title);

    void taskFailed(String taskId, String serverId, String title, String errorMessage);

    void initTaskWithSteps(String taskId, String serverId, String title,
                           List<StepInfo> steps, boolean cancellable);

    record StepInfo(String id, double weight, String title) {}
}
