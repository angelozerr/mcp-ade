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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ibm.mcp.languagetools.language.DocumentSelector;

import java.util.List;
import java.util.Map;

/**
 * DAP (Debug Adapter Protocol) configuration DTO.
 * Represents a debug adapter's static configuration.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record DapConfigDTO(
    String id,
    String name,
    String description,
    String url,
    DocumentSelector documentSelector,
    Map<String, Map<String, List<?>>> contributions,
    Boolean enabled,
    String runtime,
    String runtimeName,
    String runtimeStatus,
    String extensionId,
    Boolean hasInstaller,
    String installationStatus,
    String installDir
) implements ServerConfigDTOBase {
}
