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
import java.util.List;

public class OperationEntry {

    private static final OperationEntry NOOP = new OperationEntry();

    private final String name;
    private final String serverId;
    private final Instant startTime;
    private volatile Instant endTime;
    private volatile OperationStatus status;
    private volatile String error;
    private final List<OperationEntry> children;
    private final OperationContext context;

    private OperationEntry() {
        this.name = null;
        this.serverId = null;
        this.startTime = null;
        this.status = null;
        this.children = List.of();
        this.context = null;
    }

    static OperationEntry noop() {
        return NOOP;
    }

    OperationEntry(String name, String serverId, OperationContext context) {
        this.name = name;
        this.serverId = serverId;
        this.startTime = Instant.now();
        this.status = OperationStatus.RUNNING;
        this.children = Collections.synchronizedList(new ArrayList<>());
        this.context = context;
    }

    public OperationEntry addChild(String name) {
        return addChild(name, null);
    }

    public OperationEntry addChild(String name, String serverId) {
        if (context == null) return NOOP;
        OperationEntry child = new OperationEntry(name, serverId, context);
        children.add(child);
        context.notifyUpdate();
        return child;
    }

    public void complete() {
        if (context == null) return;
        this.endTime = Instant.now();
        this.status = OperationStatus.COMPLETED;
        context.notifyUpdate();
    }

    public void fail(String error) {
        if (context == null) return;
        this.endTime = Instant.now();
        this.status = OperationStatus.FAILED;
        this.error = error;
        context.notifyUpdate();
    }

    public String getName() {
        return name;
    }

    public String getServerId() {
        return serverId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public long getDurationMs() {
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

    public List<OperationEntry> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public OperationEntry findChild(String name) {
        for (OperationEntry child : children) {
            if (name.equals(child.getName())) {
                return child;
            }
        }
        return null;
    }
}
