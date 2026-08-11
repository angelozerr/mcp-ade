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
package com.ibm.mcp.languagetools.operation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OperationContext {

    private static final OperationContext NOOP = new OperationContext();

    private final String id;
    private final String name;
    private final String kind;
    private final String workspaceUri;
    private final Instant startTime;
    private volatile Instant endTime;
    private volatile OperationStatus status;
    private volatile String error;
    private final List<OperationEntry> entries;
    private volatile Map<String, Object> arguments;
    private volatile String result;
    private volatile String sessionId;
    private volatile String sessionName;
    private volatile OperationActor actor;
    private final OperationTracker tracker;

    private OperationContext() {
        this.id = null;
        this.name = null;
        this.kind = null;
        this.workspaceUri = null;
        this.startTime = null;
        this.status = null;
        this.entries = List.of();
        this.tracker = null;
    }

    public static OperationContext noop() {
        return NOOP;
    }

    OperationContext(String name, String kind, String workspaceUri, OperationTracker tracker) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.kind = kind;
        this.workspaceUri = workspaceUri;
        this.startTime = Instant.now();
        this.status = OperationStatus.RUNNING;
        this.entries = Collections.synchronizedList(new ArrayList<>());
        this.tracker = tracker;
    }

    public OperationEntry addEntry(String name) {
        return addEntry(name, null);
    }

    public OperationEntry addEntry(String name, String serverId) {
        if (tracker == null) {
            return OperationEntry.noop();
        }
        OperationEntry entry = new OperationEntry(name, serverId, this);
        entries.add(entry);
        notifyUpdate();
        return entry;
    }

    public void complete() {
        if (tracker == null) return;
        this.endTime = Instant.now();
        this.status = OperationStatus.COMPLETED;
        for (OperationEntry entry : entries) {
            if (entry.getStatus() == OperationStatus.RUNNING) {
                entry.complete();
            }
        }
        tracker.operationCompleted(this);
    }

    public void fail(String error) {
        if (tracker == null) return;
        this.endTime = Instant.now();
        this.status = OperationStatus.FAILED;
        this.error = error;
        for (OperationEntry entry : entries) {
            if (entry.getStatus() == OperationStatus.RUNNING) {
                entry.fail(error);
            }
        }
        tracker.operationCompleted(this);
    }

    void notifyUpdate() {
        if (tracker == null) return;
        tracker.operationUpdated(this);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public String getWorkspaceUri() {
        return workspaceUri;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getDurationMs() {
        if (startTime == null) {
            return 0;
        }
        if (endTime == null) {
            return Instant.now().toEpochMilli() - startTime.toEpochMilli();
        }
        return endTime.toEpochMilli() - startTime.toEpochMilli();
    }

    public OperationStatus getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public OperationEntry findEntryByServerId(String serverId) {
        if (tracker == null || serverId == null) return null;
        for (OperationEntry entry : entries) {
            if (serverId.equals(entry.getServerId())) {
                return entry;
            }
        }
        return null;
    }

    public List<OperationEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public void setArguments(Map<String, Object> arguments) {
        if (tracker == null) return;
        this.arguments = arguments != null ? new LinkedHashMap<>(arguments) : null;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setResult(String result) {
        if (tracker == null) return;
        this.result = result;
    }

    public String getResult() {
        return result;
    }

    public void setSessionId(String sessionId) {
        if (tracker == null) return;
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionName(String sessionName) {
        if (tracker == null) return;
        this.sessionName = sessionName;
    }

    public String getSessionName() {
        return sessionName;
    }

    public void setActor(OperationActor actor) {
        if (tracker == null) return;
        this.actor = actor;
    }

    public OperationActor getActor() {
        return actor;
    }
}
