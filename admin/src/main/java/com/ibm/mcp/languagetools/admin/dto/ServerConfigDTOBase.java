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
 * Common contract for server configuration DTOs (LSP and DAP).
 * <p>
 * Captures the 7 fields shared by both {@link LspConfigDTO} and {@link DapConfigDTO}.
 * Java records cannot extend other records, so this is expressed as an interface
 * that both records implement via their auto-generated accessor methods.
 */
public interface ServerConfigDTOBase {

    String id();

    String name();

    String description();

    String url();

    DocumentSelector documentSelector();

    Map<String, Map<String, List<?>>> contributions();

    Boolean enabled();

    String runtime();

    String runtimeStatus();

    String extensionId();

    String extensionName();

    Boolean hasInstaller();

    String installationStatus();

    String installDir();
}
