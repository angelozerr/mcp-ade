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
package org.eclipse.mcp.ade.extension;

import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.bsp.server.BspServerConfig;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * An extension groups N LSP server configs + N DAP server configs + N BSP server configs under a single id.
 * Everything in the system is an extension — even a single server added via addLspServer.
 */
public class Extension {

    private final String id;
    private String name;
    private String description;
    private final ServerConfigSource source;
    private final Application application;
    private final List<LspServerConfig> lspServerConfigs;
    private final List<DapServerConfig> dapServerConfigs;
    private final List<BspServerConfig> bspServerConfigs;
    private List<String> toolNames = Collections.emptyList();
    private List<String> profiles = Collections.emptyList();

    public Extension(String id, ServerConfigSource source, Application application) {
        this.id = id;
        this.source = source;
        this.application = application;
        this.lspServerConfigs = new ArrayList<>();
        this.dapServerConfigs = new ArrayList<>();
        this.bspServerConfigs = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ServerConfigSource getSource() {
        return source;
    }

    public Application getApplication() {
        return application;
    }

    public List<LspServerConfig> getLspServerConfigs() {
        return Collections.unmodifiableList(lspServerConfigs);
    }

    public List<DapServerConfig> getDapServerConfigs() {
        return Collections.unmodifiableList(dapServerConfigs);
    }

    public void addLspServerConfig(LspServerConfig config) {
        lspServerConfigs.add(config);
    }

    public void addDapServerConfig(DapServerConfig config) {
        dapServerConfigs.add(config);
    }

    public boolean removeLspServerConfig(String serverId) {
        return lspServerConfigs.removeIf(c -> c.getServerId().equals(serverId));
    }

    public boolean removeDapServerConfig(String serverId) {
        return dapServerConfigs.removeIf(c -> c.getServerId().equals(serverId));
    }

    public LspServerConfig getLspServerConfig(String serverId) {
        return lspServerConfigs.stream()
                .filter(c -> c.getServerId().equals(serverId))
                .findFirst()
                .orElse(null);
    }

    public DapServerConfig getDapServerConfig(String serverId) {
        return dapServerConfigs.stream()
                .filter(c -> c.getServerId().equals(serverId))
                .findFirst()
                .orElse(null);
    }

    public List<BspServerConfig> getBspServerConfigs() {
        return Collections.unmodifiableList(bspServerConfigs);
    }

    public void addBspServerConfig(BspServerConfig config) {
        bspServerConfigs.add(config);
    }

    public boolean removeBspServerConfig(String serverId) {
        return bspServerConfigs.removeIf(c -> c.getServerId().equals(serverId));
    }

    public BspServerConfig getBspServerConfig(String serverId) {
        return bspServerConfigs.stream()
                .filter(c -> c.getServerId().equals(serverId))
                .findFirst()
                .orElse(null);
    }

    public List<String> getToolNames() {
        return toolNames;
    }

    public void setToolNames(List<String> toolNames) {
        this.toolNames = toolNames != null ? toolNames : Collections.emptyList();
    }

    public int getToolsCount() {
        return toolNames.size();
    }

    /**
     * Returns the build-system profile IDs this extension supports.
     * E.g., JDT.LS returns {@code ["maven", "gradle"]}, java-ls returns {@code ["maven"]}.
     */
    public List<String> getProfiles() {
        return profiles;
    }

    /**
     * Sets the build-system profiles this extension supports.
     * Loaded from the {@code "profiles"} field in {@code mcp-extension.json}.
     */
    public void setProfiles(List<String> profiles) {
        this.profiles = profiles != null ? profiles : Collections.emptyList();
    }

    /**
     * Returns {@code true} if this extension supports the given profile.
     */
    public boolean hasProfile(String profileId) {
        return profiles.contains(profileId);
    }

    /**
     * Returns {@code true} if this extension supports any of the given profiles.
     * Used during workspace scan: detected profiles (e.g., {@code {"maven"}})
     * are checked against each extension to decide which servers to start.
     */
    public boolean hasAnyProfile(Set<String> profileIds) {
        for (String profileId : profiles) {
            if (profileIds.contains(profileId)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return lspServerConfigs.isEmpty() && dapServerConfigs.isEmpty() && bspServerConfigs.isEmpty();
    }
}
