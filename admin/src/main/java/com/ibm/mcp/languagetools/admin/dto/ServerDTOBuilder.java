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

import com.ibm.mcp.languagetools.configuration.ApplicationConfiguration;
import com.ibm.mcp.languagetools.extension.ExtensionRegistry;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import com.ibm.mcp.languagetools.server.ServerSettingDescriptor;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.workspace.Workspace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for Server DTOs (Config and Runtime).
 */
@ApplicationScoped
public class ServerDTOBuilder {

    @Inject
    ContributionDTOBuilder contributionBuilder;

    @Inject
    ExtensionRegistry extensionRegistry;

    @Inject
    ApplicationConfiguration applicationConfiguration;

    /**
     * Build LspConfigDTO from LspServerConfig.
     */
    public LspConfigDTO buildConfig(LspServerConfig config) {
        // Detect if this is an extension (contribution-only, no command)
        boolean isExtension = config.isContributionOnly();

        return new LspConfigDTO(
            config.getServerId(),
            config.getName(),
            config.getDescription(),
            config.getUrl(),
            config.getDocumentSelector(),
            config.getCommand(),
            config.getEnv(),
            config.getWorkingDirectory(),
            config.getInitializationOptions(),
            contributionBuilder.buildContributions(config),
            isExtension,
            extensionRegistry.isServerEnabled(config.getServerId()),
            buildSettings(config)
        );
    }

    /**
     * Build LspServerDTO for a server in a workspace.
     */
    public LspServerDTO buildRuntime(LspServerConfig config,
                                         Workspace workspace) {
        String serverId = config.getServerId();
        LspServer lspServer = workspace.getLspServer(serverId);

        LspServerDTO.ExternalInstanceInfo externalInfo = null;
        Long pid = null;
        String command = null;
        ServerStatus status;
        String statusMessage = null;
        boolean isReady = false;

        // Get parentServerId from config (works for both instantiated servers and contribution-only)
        String parentServerId = config.getParentServerId();

        if (parentServerId != null) {
            // Extension: use parent server's status
            LspServer parentServer = workspace.getLspServer(parentServerId);
            status = workspace.getLspServerStatus(parentServerId);
            isReady = parentServer != null && parentServer.isReady();
            statusMessage = parentServer != null ? parentServer.getStatusMessage() : null;
            pid = parentServer != null ? parentServer.getPid() : null;
            command = parentServer != null ? parentServer.getStartCommand() : null;

            if (parentServer != null) {
                var currentInstance = parentServer.getCurrentInstance();
                if (currentInstance != null) {
                    externalInfo = new LspServerDTO.ExternalInstanceInfo(
                        currentInstance.port,
                        currentInstance.pid,
                        true,
                        currentInstance.clientName,
                        currentInstance.clientVersion
                    );
                }
            }
        } else {
            // Normal server: use its own status
            if (lspServer != null) {
                var currentInstance = lspServer.getCurrentInstance();
                if (currentInstance != null) {
                    externalInfo = new LspServerDTO.ExternalInstanceInfo(
                        currentInstance.port,
                        currentInstance.pid,
                        true,
                        currentInstance.clientName,
                        currentInstance.clientVersion
                    );
                }

                pid = lspServer.getPid();
                command = lspServer.getStartCommand();
                statusMessage = lspServer.getStatusMessage();
                isReady = lspServer.isReady();
            }

            status = workspace.getLspServerStatus(serverId);
        }

        if (statusMessage != null && statusMessage.length() > 100) {
            statusMessage = statusMessage.substring(0, 97) + "...";
        }

        // Get install progress if status is INSTALLING
        Double installProgress = null;
        if (status == ServerStatus.INSTALLING) {
            var progressIndicator = config.getInstallProgress();
            if (progressIndicator != null) {
                installProgress = progressIndicator.getFraction();
            }
        }

        return new LspServerDTO(
            serverId,
            status,
            statusMessage,
            isReady,
            pid,
            command,
            externalInfo,
            parentServerId,
            installProgress
        );
    }

    private List<ServerSettingDTO> buildSettings(LspServerConfig config) {
        List<ServerSettingDescriptor> descriptors = config.getSettings();
        if (descriptors == null || descriptors.isEmpty()) {
            return null;
        }
        String serverId = config.getServerId();
        List<ServerSettingDTO> result = new ArrayList<>();
        for (ServerSettingDescriptor desc : descriptors) {
            String settingKey = "lsp." + serverId + ".settings." + desc.key();
            String currentValue = applicationConfiguration.getString(settingKey);
            if (currentValue == null) {
                currentValue = desc.defaultValue();
            }
            result.add(new ServerSettingDTO(
                    desc.key(), desc.label(), desc.description(),
                    desc.type(), desc.values(), desc.valueLabels(),
                    desc.defaultValue(), currentValue));
        }
        return result;
    }
}
