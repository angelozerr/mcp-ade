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
package org.eclipse.mcp.ade.dap.client;

import org.eclipse.lsp4j.debug.*;

/**
 * A {@link DapEventListener} that forwards all events to a delegate.
 * Subclasses can override individual methods to intercept specific events.
 */
public class ForwardingDapEventListener implements DapEventListener {

    private final DapEventListener delegate;

    public ForwardingDapEventListener(DapEventListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onInitialized() {
        delegate.onInitialized();
    }

    @Override
    public void onStopped(StoppedEventArguments event) {
        delegate.onStopped(event);
    }

    @Override
    public void onContinued(ContinuedEventArguments event) {
        delegate.onContinued(event);
    }

    @Override
    public void onExited(ExitedEventArguments event) {
        delegate.onExited(event);
    }

    @Override
    public void onTerminated(TerminatedEventArguments event) {
        delegate.onTerminated(event);
    }

    @Override
    public void onThread(ThreadEventArguments event) {
        delegate.onThread(event);
    }

    @Override
    public void onOutput(OutputEventArguments event) {
        delegate.onOutput(event);
    }

    @Override
    public void onBreakpoint(BreakpointEventArguments event) {
        delegate.onBreakpoint(event);
    }

    @Override
    public void onModule(ModuleEventArguments event) {
        delegate.onModule(event);
    }

    @Override
    public void onLoadedSource(LoadedSourceEventArguments event) {
        delegate.onLoadedSource(event);
    }

    @Override
    public void onProcess(ProcessEventArguments event) {
        delegate.onProcess(event);
    }

    @Override
    public void onCapabilities(CapabilitiesEventArguments event) {
        delegate.onCapabilities(event);
    }

    @Override
    public void onProgressStart(ProgressStartEventArguments event) {
        delegate.onProgressStart(event);
    }

    @Override
    public void onProgressUpdate(ProgressUpdateEventArguments event) {
        delegate.onProgressUpdate(event);
    }

    @Override
    public void onProgressEnd(ProgressEndEventArguments event) {
        delegate.onProgressEnd(event);
    }

    @Override
    public void onInvalidated(InvalidatedEventArguments event) {
        delegate.onInvalidated(event);
    }

    @Override
    public void onMemory(MemoryEventArguments event) {
        delegate.onMemory(event);
    }
}
