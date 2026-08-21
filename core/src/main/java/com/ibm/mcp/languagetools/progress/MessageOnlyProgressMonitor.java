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
package com.ibm.mcp.languagetools.progress;

/**
 * A progress monitor decorator that forwards only status messages
 * to a parent monitor, absorbing step transitions and completion signals.
 *
 * <p>Use this when an inner operation has its own step system (e.g., INSTALLING/STARTING)
 * that would conflict with the outer operation's steps, but you still want
 * the inner operation's progress messages to be visible to the user.</p>
 */
public class MessageOnlyProgressMonitor extends NoOpProgressMonitor {

    private final ProgressMonitor delegate;

    public MessageOnlyProgressMonitor(ProgressMonitor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void reportProgress(double progress, String message) {
        delegate.reportProgress(message);
    }

    @Override
    public void reportProgress(String message) {
        delegate.reportProgress(message);
    }

    @Override
    public boolean isSupported() {
        return delegate.isSupported();
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    @Override
    public void checkCancelled() {
        delegate.checkCancelled();
    }

    @Override
    public ProgressMonitor beginStep(String stepId) {
        return this;
    }

    @Override
    public ProgressMonitor createSubMonitor(double start, double end) {
        return this;
    }
}
