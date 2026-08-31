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

import java.util.Objects;
import java.util.UUID;

/**
 * Context information for a progress monitor.
 * Identifies what operation is being tracked.
 */
public class ProgressContext {

    private final String taskId;
    private final String serverId;
    private final String workspaceUri;
    private final String operationName;
    private final String title;

    private ProgressContext(Builder builder) {
        this.taskId = builder.taskId;
        this.serverId = builder.serverId;
        this.workspaceUri = builder.workspaceUri;
        this.operationName = builder.operationName;
        this.title = builder.title;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getServerId() {
        return serverId;
    }

    public String getWorkspaceUri() {
        return workspaceUri;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getTitle() {
        return title;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProgressContext that = (ProgressContext) o;
        return Objects.equals(taskId, that.taskId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskId);
    }

    // ==================== Builder ====================

    public static Builder builder(String title) {
        return new Builder(title);
    }

    public static class Builder {
        private String taskId;
        private String serverId;
        private String workspaceUri;
        private String operationName;
        private final String title;

        public Builder(String title) {
            Objects.requireNonNull(title, "title is required");
            this.title = title;
        }

        public Builder serverId(String serverId) {
            this.serverId = serverId;
            return this;
        }

        public Builder workspaceUri(String workspaceUri) {
            this.workspaceUri = workspaceUri;
            return this;
        }

        public Builder operationName(String operationName) {
            this.operationName = operationName;
            return this;
        }

        public ProgressContext build() {
            taskId = UUID.randomUUID().toString();
            return new ProgressContext(this);
        }
    }

    // ==================== Convenience factory methods ====================

    /**
     * Create context for a server operation (install, start, etc.).
     */
    public static ProgressContext forServer(String serverId, String title) {
        return builder(title)
                .serverId(serverId)
                .build();
    }

    /**
     * Create context for an LSP/DAP operation.
     */
    public static ProgressContext forOperation(String operationName, String title) {
        return builder(title)
                .operationName(operationName)
                .build();
    }
}
