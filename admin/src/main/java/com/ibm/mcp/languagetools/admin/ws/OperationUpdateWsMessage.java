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
package com.ibm.mcp.languagetools.admin.ws;

import com.ibm.mcp.languagetools.operation.OperationContext;
import com.ibm.mcp.languagetools.operation.OperationEntry;

import java.util.List;
import java.util.Map;

public class OperationUpdateWsMessage extends WsMessage {

    private final String eventType;
    private final String id;
    private final String name;
    private final String kind;
    private final String workspaceUri;
    private final long startTime;
    private final Long endTime;
    private final long durationMs;
    private final String status;
    private final String error;
    private final Map<String, Object> arguments;
    private final String result;
    private final String sessionId;
    private final String sessionName;
    private final String actor;
    private final List<EntryDTO> entries;

    public OperationUpdateWsMessage(String eventType, OperationContext ctx) {
        super(WsMessageType.OPERATION_UPDATE);
        this.eventType = eventType;
        this.id = ctx.getId();
        this.name = ctx.getName();
        this.kind = ctx.getKind();
        this.workspaceUri = ctx.getWorkspaceUri();
        this.startTime = ctx.getStartTime().toEpochMilli();
        this.endTime = ctx.getEndTime() != null ? ctx.getEndTime().toEpochMilli() : null;
        this.durationMs = ctx.getDurationMs();
        this.status = ctx.getStatus().name();
        this.error = ctx.getError();
        this.arguments = ctx.getArguments();
        this.result = ctx.getResult();
        this.sessionId = ctx.getSessionId();
        this.sessionName = ctx.getSessionName();
        this.actor = ctx.getActor() != null ? ctx.getActor().name() : null;
        this.entries = ctx.getEntries().stream().map(EntryDTO::from).toList();
    }

    public String getEventType() { return eventType; }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getKind() { return kind; }
    public String getWorkspaceUri() { return workspaceUri; }
    public long getStartTime() { return startTime; }
    public Long getEndTime() { return endTime; }
    public long getDurationMs() { return durationMs; }
    public String getStatus() { return status; }
    public String getError() { return error; }
    public Map<String, Object> getArguments() { return arguments; }
    public String getResult() { return result; }
    public String getSessionId() { return sessionId; }
    public String getSessionName() { return sessionName; }
    public String getActor() { return actor; }
    public List<EntryDTO> getEntries() { return entries; }

    public static class EntryDTO {
        private final String name;
        private final String serverId;
        private final long startTime;
        private final Long endTime;
        private final long durationMs;
        private final String status;
        private final String error;
        private final List<EntryDTO> children;

        private EntryDTO(OperationEntry entry) {
            this.name = entry.getName();
            this.serverId = entry.getServerId();
            this.startTime = entry.getStartTime().toEpochMilli();
            this.endTime = entry.getEndTime() != null ? entry.getEndTime().toEpochMilli() : null;
            this.durationMs = entry.getDurationMs();
            this.status = entry.getStatus().name();
            this.error = entry.getError();
            this.children = entry.getChildren().stream().map(EntryDTO::from).toList();
        }

        public static EntryDTO from(OperationEntry entry) {
            return new EntryDTO(entry);
        }

        public String getName() { return name; }
        public String getServerId() { return serverId; }
        public long getStartTime() { return startTime; }
        public Long getEndTime() { return endTime; }
        public long getDurationMs() { return durationMs; }
        public String getStatus() { return status; }
        public String getError() { return error; }
        public List<EntryDTO> getChildren() { return children; }
    }
}
