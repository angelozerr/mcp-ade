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
package org.eclipse.mcp.ade.admin;

import org.eclipse.mcp.ade.admin.dto.ContributionDTOBuilder;
import org.eclipse.mcp.ade.admin.dto.DapConfigDTO;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.eclipse.mcp.ade.extension.ExtensionRegistry;
import org.eclipse.mcp.ade.trace.TraceCollector;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                .map(this::toDTOSummary)
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

    @GET
    @Path("/configs/{serverId}/contributions")
    public Map<String, Object> getContributions(@PathParam("serverId") String serverId) {
        DapServerConfig config = application.getDapServerConfig(serverId);
        if (config == null) {
            throw new NotFoundException("DAP server not found: " + serverId);
        }
        List<ServerConfigBase> allConfigs = new ArrayList<>();
        allConfigs.addAll(application.getLspServerConfigs());
        allConfigs.addAll(application.getDapServerConfigs());
        return contributionBuilder.buildContributionsView(serverId, config, allConfigs);
    }

    private static Boolean trueOrNull(boolean value) {
        return value ? Boolean.TRUE : null;
    }

    private static String extensionName(DapServerConfig config) {
        return config.getExtensionName();
    }

    private DapConfigDTO toDTO(DapServerConfig config) {
        boolean hasInstaller = config.getInstaller() != null;
        return new DapConfigDTO(
            config.getServerId(),
            config.getName(),
            config.getDescription(),
            config.getUrl(),
            config.getDocumentSelector(),
            contributionBuilder.buildContributions(config),
            trueOrNull(extensionRegistry.isServerEnabled(config.getServerId())),
            config.getRuntime(),
            config.getRuntimeConfig() != null ? config.getRuntimeConfig().getName() : null,
            config.getRuntimeStatusName(),
            config.getExtensionId(),
            extensionName(config),
            trueOrNull(hasInstaller),
            hasInstaller ? config.getStatus().name() : null,
            hasInstaller ? config.getServerHome().toString() : null
        );
    }

    private DapConfigDTO toDTOSummary(DapServerConfig config) {
        boolean hasInstaller = config.getInstaller() != null;
        return new DapConfigDTO(
            config.getServerId(),
            config.getName(),
            null,
            null,
            config.getDocumentSelector(),
            null,
            trueOrNull(extensionRegistry.isServerEnabled(config.getServerId())),
            null,
            null,
            null,
            null,
            null,
            trueOrNull(hasInstaller),
            hasInstaller ? config.getStatus().name() : null,
            null
        );
    }

}
