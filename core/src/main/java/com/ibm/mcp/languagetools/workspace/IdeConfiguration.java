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
package com.ibm.mcp.languagetools.workspace;

import com.ibm.mcp.languagetools.configuration.AbstractConfiguration;
import com.ibm.mcp.languagetools.configuration.FileWatcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IDE configuration reader.
 * Loads settings from IDE-supplied files (e.g., .vscode/settings.json, .bob/settings.json)
 * using a configurable strategy (first-found or merge).
 */
public class IdeConfiguration extends AbstractConfiguration {

    private final Path workspaceRoot;
    private final List<IdeConfigurationProvider> providers;
    private final IdeConfigurationStrategy strategy;
    private final List<FileWatcher> fileWatchers = new ArrayList<>();

    /**
     * Creates an IDE configuration with explicit providers and strategy.
     */
    public IdeConfiguration(Path workspaceRoot,
                            List<IdeConfigurationProvider> providers,
                            IdeConfigurationStrategy strategy) {
        this.workspaceRoot = workspaceRoot;
        this.providers = providers;
        this.strategy = strategy;
        load();
    }

    /**
     * Creates an IDE configuration with default providers (all SPI providers, FIRST_FOUND).
     */
    public IdeConfiguration(Path workspaceRoot) {
        this(workspaceRoot,
                new ArrayList<>(IdeConfigurationProviderRegistry.getInstance().getProviders()),
                IdeConfigurationStrategy.FIRST_FOUND);
    }

    @Override
    protected void load() {
        switch (strategy) {
            case FIRST_FOUND -> loadFirstFound();
            case MERGE -> loadMerge();
        }
    }

    private void loadFirstFound() {
        for (IdeConfigurationProvider provider : providers) {
            Path file = provider.getSettingsFile(workspaceRoot);
            if (Files.exists(file)) {
                Map<String, Object> loaded = loadFromFile(file);
                getSettings().clear();
                getSettings().putAll(loaded);
                return;
            }
        }
        getSettings().clear();
    }

    private void loadMerge() {
        Map<String, Object> merged = new HashMap<>();
        // Load in reverse order so that first provider's values win
        for (int i = providers.size() - 1; i >= 0; i--) {
            Path file = providers.get(i).getSettingsFile(workspaceRoot);
            if (Files.exists(file)) {
                Map<String, Object> loaded = loadFromFile(file);
                merged.putAll(loaded);
            }
        }
        getSettings().clear();
        getSettings().putAll(merged);
    }

    @Override
    protected Path getSettingsFile() {
        for (IdeConfigurationProvider provider : providers) {
            Path file = provider.getSettingsFile(workspaceRoot);
            if (Files.exists(file)) {
                return file;
            }
        }
        return providers.isEmpty() ? null : providers.get(0).getSettingsFile(workspaceRoot);
    }

    @Override
    public void watch() {
        for (IdeConfigurationProvider provider : providers) {
            Path file = provider.getSettingsFile(workspaceRoot);
            FileWatcher watcher = new FileWatcher(file, this::reload);
            watcher.start();
            fileWatchers.add(watcher);
        }
    }

    @Override
    public void unwatch() {
        fileWatchers.forEach(FileWatcher::stop);
        fileWatchers.clear();
    }
}
