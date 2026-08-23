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
package com.ibm.mcp.languagetools.installer;

import com.google.gson.JsonElement;
import com.ibm.mcp.languagetools.trace.TraceCollector;

import java.nio.file.Path;

/**
 * Common interface for installable components (servers and runtimes).
 * Provides the minimal contract needed by the installer infrastructure.
 */
public interface InstallableConfig {

    /**
     * Returns the unique identifier (e.g. "jdtls", "jdk", "nodejs").
     */
    String getServerId();

    /**
     * Returns the human-readable name.
     */
    String getName();

    /**
     * Returns the installation home directory.
     */
    Path getServerHome();

    /**
     * Returns the installer configuration JSON from installer.json.
     */
    JsonElement getInstallerConfig();

    /**
     * Returns the trace collector for logging installation progress.
     */
    TraceCollector getTraceCollector();
}
