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
package com.ibm.mcp.languagetools.extension;

import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.bsp.server.BspServerConfig;
import com.ibm.mcp.languagetools.dap.server.DapServerConfig;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An extension groups N LSP server configs + N DAP server configs + N BSP server configs under a single id.
 * Everything in the system is an extension — even a single server added via addLspServer.
 */
public class Extension {

    private final String id;
    private final ServerConfigSource source;
    private final Application application;
    private final List<LspServerConfig> lspServerConfigs;
    private final List<DapServerConfig> dapServerConfigs;
    private final List<BspServerConfig> bspServerConfigs;

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

    public boolean isEmpty() {
        return lspServerConfigs.isEmpty() && dapServerConfigs.isEmpty() && bspServerConfigs.isEmpty();
    }
}
