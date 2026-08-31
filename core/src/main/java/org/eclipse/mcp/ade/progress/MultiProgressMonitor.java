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

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A progress monitor that broadcasts progress to multiple delegates.
 *
 * Use case: When an operation is triggered by MCP client, we want to report progress to:
 * 1. The MCP client (via MCP protocol)
 * 2. The Admin UI (via TraceCollector WebSocket)
 *
 * Example:
 * <pre>
 * ProgressMonitor mcpMonitor = new McpProgressMonitor(mcpClient);
 * ProgressMonitor traceMonitor = new TraceProgressMonitor(traceCollector);
 *
 * ProgressMonitor multi = new MultiProgressMonitor(mcpMonitor, traceMonitor);
 * multi.reportProgress(50, "Downloading...");
 * // → Both MCP client and Admin UI receive the update
 * </pre>
 */
public class MultiProgressMonitor implements ProgressMonitor {

    private static final Logger LOG = Logger.getLogger(MultiProgressMonitor.class);

    private final List<ProgressMonitor> delegates;
    private final double total;

    /**
     * Create a multi progress monitor with the given delegates.
     * All delegates must have the same total.
     */
    public MultiProgressMonitor(ProgressMonitor... delegates) {
        this(100.0, delegates);
    }

    /**
     * Create a multi progress monitor with a specific total.
     */
    public MultiProgressMonitor(double total, ProgressMonitor... delegates) {
        if (delegates == null || delegates.length == 0) {
            throw new IllegalArgumentException("At least one delegate is required");
        }
        this.delegates = new CopyOnWriteArrayList<>(Arrays.asList(delegates));
        this.total = total;
    }

    /**
     * Add another delegate monitor.
     */
    public void addDelegate(ProgressMonitor delegate) {
        delegates.add(delegate);
    }

    @Override
    public void reportProgress(double progress, String message) {
        for (ProgressMonitor delegate : delegates) {
            try {
                delegate.reportProgress(progress, message);
            } catch (Exception e) {
                LOG.warnf(e, "Progress delegate failed");
            }
        }
    }

    @Override
    public void reportProgress(String message) {
        for (ProgressMonitor delegate : delegates) {
            try {
                delegate.reportProgress(message);
            } catch (Exception e) {
                LOG.warnf(e, "Progress delegate failed");
            }
        }
    }

    @Override
    public void setComplete() {
        for (ProgressMonitor delegate : delegates) {
            try {
                delegate.setComplete();
            } catch (Exception e) {
                LOG.warnf(e, "Progress delegate failed");
            }
        }
    }

    @Override
    public double getTotal() {
        return total;
    }

    @Override
    public boolean isCancelled() {
        // Consider cancelled if ANY delegate is cancelled
        for (ProgressMonitor delegate : delegates) {
            if (delegate.isCancelled()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void checkCancelled() {
        // Check all delegates - throw if any is cancelled
        for (ProgressMonitor delegate : delegates) {
            delegate.checkCancelled();
        }
    }

    @Override
    public <T> CompletableFuture<T> executeWithCancellation(CompletableFuture<T> future) {
        // Use the first delegate's cancellation logic
        // (they should all behave the same for cancellation)
        return delegates.get(0).executeWithCancellation(future);
    }

    @Override
    public boolean isSupported() {
        // Supported if at least one delegate supports it
        for (ProgressMonitor delegate : delegates) {
            if (delegate.isSupported()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the list of delegates.
     */
    public List<ProgressMonitor> getDelegates() {
        return new ArrayList<>(delegates);
    }

    @Override
    public ProgressMonitor addStep(String stepId, double weight) {
        for (ProgressMonitor delegate : delegates) {
            try {
                delegate.addStep(stepId, weight);
            } catch (Exception e) {
                LOG.warnf(e, "Progress delegate failed");
            }
        }
        return this;
    }

    @Override
    public ProgressMonitor beginStep(String stepId) {
        // Begin step on all delegates and return a multi-monitor for sub-tasks
        ProgressMonitor[] subDelegates = delegates.stream()
                .map(delegate -> {
                    try {
                        return delegate.beginStep(stepId);
                    } catch (Exception e) {
                        return ProgressMonitor.none();
                    }
                })
                .toArray(ProgressMonitor[]::new);

        return new MultiProgressMonitor(1.0, subDelegates);
    }

    @Override
    public void completeStep(String stepId) {
        for (ProgressMonitor delegate : delegates) {
            try {
                delegate.completeStep(stepId);
            } catch (Exception e) {
                LOG.warnf(e, "Progress delegate failed");
            }
        }
    }

    @Override
    public String startTask(String taskId) {
        for (ProgressMonitor delegate : delegates) {
            try {
                delegate.startTask(taskId);
            } catch (Exception e) {
                LOG.warnf(e, "Progress delegate failed");
            }
        }
        return taskId;
    }

    @Override
    public void endTask(String taskId) {
        for (ProgressMonitor delegate : delegates) {
            try {
                delegate.endTask(taskId);
            } catch (Exception e) {
                LOG.warnf(e, "Progress delegate failed");
            }
        }
    }

    @Override
    public void cancel(String taskId) {
        for (ProgressMonitor delegate : delegates) {
            try {
                delegate.cancel(taskId);
            } catch (Exception e) {
                LOG.warnf(e, "Progress delegate failed");
            }
        }
    }

    @Override
    public ProgressMonitor createSubMonitor(double start, double end) {
        // Create sub-monitors on all delegates
        ProgressMonitor[] subDelegates = delegates.stream()
                .map(delegate -> {
                    try {
                        return delegate.createSubMonitor(start, end);
                    } catch (Exception e) {
                        return ProgressMonitor.none();
                    }
                })
                .toArray(ProgressMonitor[]::new);

        return new MultiProgressMonitor(1.0, subDelegates);
    }
}
