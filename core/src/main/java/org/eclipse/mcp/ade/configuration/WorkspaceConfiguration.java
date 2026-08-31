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
package org.eclipse.mcp.ade.configuration;

import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

/**
 * Per-workspace application configuration stored in {workspace-root}/.mcp-ade/settings.json.
 * <p>
 * These settings override the global {@link ApplicationConfiguration}.
 * Only keys explicitly set at workspace level are stored; all other keys
 * fall back to the global configuration via the {@code resolve*} methods.
 */
public class WorkspaceConfiguration extends AbstractConfiguration {

    private static final Logger LOG = Logger.getLogger(WorkspaceConfiguration.class);
    private static final String SETTINGS_DIR = ".mcp-ade";
    private static final String SETTINGS_FILE = "settings.json";

    private final Path settingsFile;
    private final Configuration globalConfiguration;

    public WorkspaceConfiguration(Path workspaceRoot, Configuration globalConfiguration) {
        this.settingsFile = workspaceRoot.resolve(SETTINGS_DIR).resolve(SETTINGS_FILE);
        this.globalConfiguration = globalConfiguration;
        load();
    }

    @Override
    protected Path getSettingsFile() {
        return settingsFile;
    }

    /**
     * Check if a key has been overridden at workspace level.
     */
    public boolean has(String key) {
        return getSettings().containsKey(key);
    }

    /**
     * Return all workspace-level overrides (only keys set at workspace level).
     */
    public Map<String, Object> getOverrides() {
        return Collections.unmodifiableMap(getSettings());
    }

    // ========== Resolved settings (workspace → global → default) ==========

    /**
     * Resolve a boolean setting with inheritance: workspace override → global → default.
     */
    public ResolvedConfiguration<Boolean> resolveBoolean(String key, boolean defaultValue) {
        if (has(key)) {
            return new ResolvedConfiguration<>(getBoolean(key, defaultValue), ConfigurationSource.WORKSPACE);
        }
        if (globalConfiguration != null && globalConfiguration.get(key) != null) {
            return new ResolvedConfiguration<>(globalConfiguration.getBoolean(key, defaultValue), ConfigurationSource.APPLICATION);
        }
        return new ResolvedConfiguration<>(defaultValue, ConfigurationSource.DEFAULT);
    }

    /**
     * Resolve a string setting with inheritance: workspace override → global → default.
     */
    public ResolvedConfiguration<String> resolveString(String key, String defaultValue) {
        if (has(key)) {
            String val = getString(key);
            return new ResolvedConfiguration<>(val != null ? val : defaultValue, ConfigurationSource.WORKSPACE);
        }
        if (globalConfiguration != null) {
            String val = globalConfiguration.getString(key, null);
            if (val != null) {
                return new ResolvedConfiguration<>(val, ConfigurationSource.APPLICATION);
            }
        }
        return new ResolvedConfiguration<>(defaultValue, ConfigurationSource.DEFAULT);
    }

    // ========== Trace levels (resolved with inheritance) ==========

    @Override
    public ServerTrace getLspTraceLevel(String serverId) {
        return ServerTrace.fromValue(resolveString("lsp." + serverId + ".trace", ServerTrace.off.toString()).value());
    }

    public void resetLspTraceLevel(String serverId) {
        remove("lsp." + serverId + ".trace");
    }

    @Override
    public ServerTrace getDapTraceLevel(String serverId) {
        return ServerTrace.fromValue(resolveString("dap." + serverId + ".trace", ServerTrace.off.toString()).value());
    }

    public void resetDapTraceLevel(String serverId) {
        remove("dap." + serverId + ".trace");
    }

    @Override
    public ServerTrace getBspTraceLevel(String serverId) {
        return ServerTrace.fromValue(resolveString("bsp." + serverId + ".trace", ServerTrace.off.toString()).value());
    }

    public void resetBspTraceLevel(String serverId) {
        remove("bsp." + serverId + ".trace");
    }
}
