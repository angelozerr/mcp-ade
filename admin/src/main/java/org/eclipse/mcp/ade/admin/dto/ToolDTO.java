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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Set;

@RegisterForReflection
public record ToolDTO(
        String name,
        String description,
        String group,
        String subGroup,
        Set<String> serverNames,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String extensionId,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String extensionName,
        List<ToolArgumentDTO> args
) {

    @RegisterForReflection
    public record ToolArgumentDTO(
            String name,
            String description,
            boolean required,
            String type
    ) {
    }
}
