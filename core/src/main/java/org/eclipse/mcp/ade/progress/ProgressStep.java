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

/**
 * Standard progress steps for LSP server lifecycle operations.
 */
public enum ProgressStep {

    CHECKING("Checking"),
    INSTALLING_RUNTIME("Installing runtime"),
    INSTALLING("Installing"),
    STARTING("Starting"),
    INITIALIZING("Initializing"),
    INDEXING("Indexing"),
    EXECUTING("Executing");

    private final String label;

    ProgressStep(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
