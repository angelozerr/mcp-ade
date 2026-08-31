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
package org.eclipse.mcp.ade.workspace;

import java.nio.file.Path;

/**
 * SPI for IDE configuration file providers.
 * Each provider knows how to locate a settings file within a workspace root
 * (e.g., .vscode/settings.json, .bob/settings.json).
 */
public interface IdeConfigurationProvider {

    /**
     * Returns the unique identifier for this provider (e.g., "vscode", "bob").
     */
    String getId();

    /**
     * Returns the path to the settings file for the given workspace root.
     *
     * @param workspaceRoot the workspace root directory
     * @return the path to the settings file (may or may not exist on disk)
     */
    Path getSettingsFile(Path workspaceRoot);
}
