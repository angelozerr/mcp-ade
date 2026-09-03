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
import org.eclipse.mcp.ade.extension.Extension;
import org.eclipse.mcp.ade.extension.ExtensionRegistry;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ExtensionDTO(
        String id,
        String name,
        String description,
        String source,
        Boolean enabled,
        List<ServerInfo> lspServers,
        List<ServerInfo> dapServers,
        List<ServerInfo> bspServers
) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record ServerInfo(String id, String name, Boolean enabled) {}

    public static ExtensionDTO fromExtension(Extension ext, ExtensionRegistry registry) {
        List<ServerInfo> lspServers = ext.getLspServerConfigs().stream()
                .map(c -> new ServerInfo(c.getServerId(), c.getName(),
                        registry.isServerEnabled(c.getServerId()) ? Boolean.TRUE : null))
                .toList();

        List<ServerInfo> dapServers = ext.getDapServerConfigs().stream()
                .map(c -> new ServerInfo(c.getServerId(), c.getName(),
                        registry.isServerEnabled(c.getServerId()) ? Boolean.TRUE : null))
                .toList();

        List<ServerInfo> bspServers = ext.getBspServerConfigs().stream()
                .map(c -> new ServerInfo(c.getServerId(), c.getName(),
                        registry.isServerEnabled(c.getServerId()) ? Boolean.TRUE : null))
                .toList();

        return new ExtensionDTO(
                ext.getId(),
                ext.getName(),
                ext.getDescription(),
                ext.getSource().name(),
                registry.isExtensionEnabled(ext.getId()) ? Boolean.TRUE : null,
                lspServers,
                dapServers,
                bspServers
        );
    }
}
