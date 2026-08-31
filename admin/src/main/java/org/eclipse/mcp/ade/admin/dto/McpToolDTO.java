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
package org.eclipse.mcp.ade.admin.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Set;

@RegisterForReflection
public record McpToolDTO(
        String name,
        String description,
        String group,
        String subGroup,
        Set<String> serverNames,
        List<McpToolArgumentDTO> args
) {

    @RegisterForReflection
    public record McpToolArgumentDTO(
            String name,
            String description,
            boolean required,
            String type
    ) {
    }
}
