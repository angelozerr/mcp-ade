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
package com.ibm.mcp.languagetools.admin.dto;

import com.ibm.mcp.languagetools.language.DocumentSelector;

import java.util.List;
import java.util.Map;

/**
 * LSP server configuration - immutable server descriptor.
 * This represents the LSP server's definition independent of any workspace or runtime state.
 */
public record LspConfigDTO(
    String id,
    String name,
    String description,
    String url,
    DocumentSelector documentSelector,
    String command,
    Map<String, String> env,
    String workingDirectory,
    Map<String, Object> initializationOptions,
    Map<String, Map<String, List<?>>> contributions,
    boolean isExtension,
    boolean enabled,
    List<ServerSettingDTO> settings,
    String runtime,
    String runtimeStatus,
    String extensionId,
    boolean hasInstaller,
    String installationStatus,
    String installDir
) implements ServerConfigDTOBase {
}
