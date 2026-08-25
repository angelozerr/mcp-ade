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
package com.ibm.mcp.languagetools.admin;

import com.ibm.mcp.languagetools.admin.dto.ContributionDTOBuilder;
import com.ibm.mcp.languagetools.admin.dto.DapConfigDTO;
import com.ibm.mcp.languagetools.dap.server.DapServerConfig;
import com.ibm.mcp.languagetools.extension.ExtensionRegistry;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * REST endpoint for all DAP-related admin operations.
 * Consolidates DAP config listing, details, installer, sessions, and templates.
 */
@Path("/api/admin/dap")
@Produces(MediaType.APPLICATION_JSON)
public class DapAdminResource extends AbstractServerAdminResource {

    @Inject
    ContributionDTOBuilder contributionBuilder;

    @Inject
    ExtensionRegistry extensionRegistry;

    @Override
    protected ServerConfigBase getServerConfig(String serverId) {
        return application.getDapServerConfig(serverId);
    }

    @Override
    protected String getServerType() {
        return "DAP";
    }

    @Override
    protected TraceCollector getTraceCollector() {
        return application.getDapTraceCollector();
    }

    // ========== DAP Configs ==========

    /**
     * List all configured DAP servers (static config).
     */
    @GET
    @Path("/configs")
    public List<DapConfigDTO> listConfigs() {
        var configs = application.getDapServerConfigs();
        checkUncheckedServers(configs);
        return configs.stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * Get details of a specific DAP config.
     */
    @GET
    @Path("/configs/{serverId}")
    public DapConfigDTO getConfig(@PathParam("serverId") String serverId) {
        DapServerConfig config = application.getDapServerConfig(serverId);

        if (config == null) {
            throw new NotFoundException("DAP server not found: " + serverId);
        }

        return toDTO(config);
    }

    private DapConfigDTO toDTO(DapServerConfig config) {
        return new DapConfigDTO(
            config.getServerId(),
            config.getName(),
            config.getDescription(),
            config.getUrl(),
            config.getDocumentSelector(),
            contributionBuilder.buildContributions(config),
            extensionRegistry.isServerEnabled(config.getServerId()),
            config.getRuntime(),
            config.getRuntimeStatusName(),
            config.getExtensionId(),
            config.getInstaller() != null,
            config.getInstaller() != null ? config.getStatus().name() : null,
            config.getInstaller() != null ? config.getServerHome().toString() : null
        );
    }

}
