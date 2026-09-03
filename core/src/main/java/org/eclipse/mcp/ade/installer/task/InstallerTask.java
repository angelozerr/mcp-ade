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
package org.eclipse.mcp.ade.installer.task;

import org.eclipse.mcp.ade.installer.InstallerContext;

/**
 * Base class for installer tasks.
 * Uses template method pattern: {@link #execute} handles common logic
 * (cancel check, progress step, onSuccess chaining), subclasses implement {@link #run}.
 */
public abstract class InstallerTask {

    private final String name;
    private final InstallerTask onSuccess;
    private final InstallerTask onFail;

    protected InstallerTask(String name, InstallerTask onSuccess) {
        this(name, onSuccess, null);
    }

    protected InstallerTask(String name, InstallerTask onSuccess, InstallerTask onFail) {
        this.name = name;
        this.onSuccess = onSuccess;
        this.onFail = onFail;
    }

    /**
     * Executes the task with common logic: cancel check, progress step, onSuccess/onFail chaining.
     *
     * @param context Installation context
     * @return true if task (and onSuccess/onFail chain) succeeded, false otherwise
     */
    public final boolean execute(InstallerContext context) {
        context.checkCanceled();
        context.getProgress().beginStep(getName());
        if (run(context)) {
            if (onSuccess != null) {
                return onSuccess.execute(context);
            }
            return true;
        }
        if (onFail != null) {
            return onFail.execute(context);
        }
        return false;
    }

    /**
     * Task-specific logic. Subclasses implement this instead of {@link #execute}.
     *
     * @param context Installation context
     * @return true if task succeeded
     */
    protected abstract boolean run(InstallerContext context);

    public String getName() {
        return name;
    }

    public InstallerTask getOnSuccess() {
        return onSuccess;
    }

    public InstallerTask getOnFail() {
        return onFail;
    }
}
