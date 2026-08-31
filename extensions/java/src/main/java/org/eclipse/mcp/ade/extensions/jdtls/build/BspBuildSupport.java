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
package org.eclipse.mcp.ade.extensions.jdtls.build;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.eclipse.mcp.ade.bsp.server.BspServer;
import org.eclipse.mcp.ade.progress.ProgressMonitor;

/**
 * Provides classpath extraction via the Build Server Protocol (BSP).
 *
 * <p>Implementations are discovered via CDI and used by {@link BuildSupportManager}
 * when BSP mode is enabled for the workspace.</p>
 */
public interface BspBuildSupport {

    /**
     * Returns the BSP server identifier (e.g., {@code "build-server-for-gradle"}).
     */
    String getBspServerId();

    /**
     * Extracts classpath information for a module via BSP.
     *
     * @param bspServer     the ready BSP server
     * @param workspaceRoot the workspace root directory
     * @param moduleDir     the module directory to extract classpath for
     * @param progress      progress monitor
     * @return a future completing with the extracted classpath info
     */
    CompletableFuture<ClasspathInfo> extractAsync(BspServer bspServer,
                                                   Path workspaceRoot, Path moduleDir,
                                                   ProgressMonitor progress);
}
