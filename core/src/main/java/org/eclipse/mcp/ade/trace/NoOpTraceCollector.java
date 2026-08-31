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
package org.eclipse.mcp.ade.trace;

/**
 * No-op implementation of {@link TraceCollector}.
 * {@link #isEnabled()} returns false so callers skip message creation entirely.
 */
public class NoOpTraceCollector implements TraceCollector {

    public static final NoOpTraceCollector INSTANCE = new NoOpTraceCollector();

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void addTrace(String workspaceUri, String contextId, String content, MessageType type) {
    }
}
