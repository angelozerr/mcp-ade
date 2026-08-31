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
package org.eclipse.mcp.ade.server;

import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.PathManager;
import org.eclipse.mcp.ade.bsp.server.BspServerDescriptorLoader;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.eclipse.mcp.ade.dap.server.DapServerDescriptorLoader;
import org.eclipse.mcp.ade.extension.Extension;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.eclipse.mcp.ade.lsp.server.LspServerDescriptorLoader;
import org.eclipse.mcp.ade.runtime.RuntimeConfig;
import org.eclipse.mcp.ade.runtime.RuntimeDescriptorLoader;
import org.eclipse.mcp.ade.runtime.RuntimeRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * Registry that discovers and loads all server configurations (LSP and DAP).
 * Scans resource directories once and delegates to appropriate loaders.
 */
@ApplicationScoped
public class ServerDescriptorRegistry {

    private static final Logger LOG = Logger.getLogger(ServerDescriptorRegistry.class);

    private final Map<String, ServerDescriptorLoaderBase<?>> loaders = new HashMap<>();
    private final RuntimeDescriptorLoader runtimeDescriptorLoader = new RuntimeDescriptorLoader();

    @Inject
    LspServerDescriptorLoader lspServerDescriptorLoader;

    @Inject
    DapServerDescriptorLoader dapServerDescriptorLoader;

    @Inject
    BspServerDescriptorLoader bspServerDescriptorLoader;

    @Inject
    RuntimeRegistry runtimeRegistry;

    @Inject
    PathManager pathManager;

    void onStart(@Observes @Priority(1) StartupEvent ev) {
        LOG.info("ServerDescriptorRegistry starting...");
        // Register loaders on startup
        registerLoader(lspServerDescriptorLoader);
        registerLoader(dapServerDescriptorLoader);
        registerLoader(bspServerDescriptorLoader);
        LOG.infof("ServerDescriptorRegistry initialized with %d loaders", loaders.size());
    }

    /**
     * Register a loader for a specific root directory.
     */
    public <T extends ServerConfigBase> void registerLoader(ServerDescriptorLoaderBase<T> loader) {
        String root = loader.getRoot();
        loaders.put(root, loader);
        LOG.infof("Registered loader for root: %s", root);
    }

    /**
     * Load all bundled servers by scanning all registered roots.
     * Returns combined map of all server configs (LSP + DAP).
     */
    public Map<String, ServerConfigBase> loadAllBundled(Application application) {
        Map<String, ServerConfigBase> allConfigs = new HashMap<>();

        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            LOG.infof("Using ClassLoader: %s", classLoader.getClass().getName());

            // Scan each registered root
            for (String root : loaders.keySet()) {
                LOG.infof("Scanning for servers in root: %s", root);
                Enumeration<URL> resources = classLoader.getResources(root);

                int resourceCount = 0;
                while (resources.hasMoreElements()) {
                    URL dirUrl = resources.nextElement();
                    resourceCount++;
                    LOG.infof("Found resource #%d for root '%s': %s", resourceCount, root, dirUrl);
                    scanDirectory(dirUrl, root, allConfigs, application);
                }

                if (resourceCount == 0) {
                    LOG.warnf("No resources found for root: %s", root);
                }
            }

        } catch (Exception e) {
            LOG.errorf(e, "Failed to load bundled servers");
        }

        LOG.infof("Loaded %d total servers", allConfigs.size());
        return allConfigs;
    }

    /**
     * Load LSP servers only.
     */
    public Map<String, LspServerConfig> loadAllLspServers(Application application) {
        Map<String, LspServerConfig> lspConfigs = new HashMap<>();
        Map<String, ServerConfigBase> all = loadAllBundled(application);

        for (Map.Entry<String, ServerConfigBase> entry : all.entrySet()) {
            if (entry.getValue() instanceof LspServerConfig) {
                lspConfigs.put(entry.getKey(), (LspServerConfig) entry.getValue());
            }
        }

        return lspConfigs;
    }

    /**
     * Load DAP servers only.
     */
    public Map<String, DapServerConfig> loadAllDapServers(Application application) {
        Map<String, DapServerConfig> dapConfigs = new HashMap<>();
        Map<String, ServerConfigBase> all = loadAllBundled(application);

        for (Map.Entry<String, ServerConfigBase> entry : all.entrySet()) {
            if (entry.getValue() instanceof DapServerConfig) {
                dapConfigs.put(entry.getKey(), (DapServerConfig) entry.getValue());
            }
        }

        return dapConfigs;
    }

    /**
     * Load all servers from an extension directory on the filesystem.
     * Scans runtime/, lsp/, dap/, and bsp/ subdirectories within the extension dir.
     *
     * @param extensionDir the extension directory (e.g., extensions/jdtls/)
     * @param extension    the Extension object
     * @return map of serverId → config
     */
    public Map<String, ServerConfigBase> loadFromExtensionDir(Path extensionDir, Extension extension) {
        Map<String, ServerConfigBase> configs = new HashMap<>();

        // Load runtimes first
        loadRuntimesFromExtensionDir(extensionDir, extension);

        // Load servers (lsp, dap, bsp)
        for (Map.Entry<String, ServerDescriptorLoaderBase<?>> entry : loaders.entrySet()) {
            String root = entry.getKey();
            ServerDescriptorLoaderBase<?> loader = entry.getValue();

            Path typeDir = extensionDir.resolve(root);
            if (!java.nio.file.Files.isDirectory(typeDir)) {
                continue;
            }

            try (var entries = java.nio.file.Files.list(typeDir)) {
                entries.filter(java.nio.file.Files::isDirectory)
                       .forEach(serverDir -> {
                           String serverId = serverDir.getFileName().toString();
                           if (configs.containsKey(serverId)) {
                               LOG.debugf("Skipping duplicate server: %s", serverId);
                               return;
                           }
                           try {
                               ServerConfigBase config = loader.load(serverDir, extension);
                               configs.put(serverId, config);
                               LOG.infof("Loaded server: %s from extension %s/%s", serverId, extension.getId(), root);
                           } catch (Exception e) {
                               LOG.errorf(e, "Failed to load server %s from extension %s", serverId, extension.getId());
                           }
                       });
            } catch (Exception e) {
                LOG.warnf(e, "Failed to scan %s directory in extension %s", root, extension.getId());
            }
        }

        // Wire installer runtimes for runtimes (must be after all runtimes are registered)
        runtimeRegistry.wireAllInstallerRuntimes();

        // Wire servers to runtimes (also wires installer runtimes for each server)
        for (ServerConfigBase config : configs.values()) {
            runtimeRegistry.wireServer(config);
        }

        return configs;
    }

    /**
     * Load runtimes from an extension directory.
     */
    private void loadRuntimesFromExtensionDir(Path extensionDir, Extension extension) {
        Path runtimeDir = extensionDir.resolve(RuntimeDescriptorLoader.ROOT);
        if (!java.nio.file.Files.isDirectory(runtimeDir)) {
            return;
        }

        try (var entries = java.nio.file.Files.list(runtimeDir)) {
            entries.filter(java.nio.file.Files::isDirectory)
                   .forEach(dir -> {
                       String runtimeId = dir.getFileName().toString();
                       if (runtimeRegistry.get(runtimeId) != null) {
                           LOG.debugf("Skipping duplicate runtime: %s", runtimeId);
                           return;
                       }
                       try {
                           Path runtimeHome = pathManager.getRuntimeHome(runtimeId);
                           RuntimeConfig config = runtimeDescriptorLoader.load(dir, runtimeHome, extension);
                           runtimeRegistry.register(config);
                       } catch (Exception e) {
                           LOG.errorf(e, "Failed to load runtime %s from extension %s", runtimeId, extension.getId());
                       }
                   });
        } catch (Exception e) {
            LOG.warnf(e, "Failed to scan runtime directory in extension %s", extension.getId());
        }
    }

    /**
     * Get the loader for a given root type ("lsp" or "dap").
     */
    public ServerDescriptorLoaderBase<?> getLoader(String root) {
        return loaders.get(root);
    }

    private void scanDirectory(URL dirUrl, String root, Map<String, ServerConfigBase> configs, Application application) {
        try {
            LOG.infof("Scanning directory URL: %s for root: %s", dirUrl, root);
            URI dirUri = dirUrl.toURI();
            Path dirPath;
            FileSystem fs = null;

            if (dirUri.getScheme().equals("jar")) {
                // Running from JAR
                try {
                    fs = FileSystems.newFileSystem(dirUri, Collections.emptyMap());
                    dirPath = fs.getPath("/" + root);
                } catch (FileSystemAlreadyExistsException e) {
                    fs = FileSystems.getFileSystem(dirUri);
                    dirPath = fs.getPath("/" + root);
                }
            } else {
                // Running from filesystem
                dirPath = Paths.get(dirUri);
            }

            LOG.infof("Scanning directory path: %s", dirPath);

            // Scan for server directories
            try (var entries = Files.list(dirPath)) {
                entries.filter(Files::isDirectory)
                       .forEach(serverDir -> {
                           String serverId = serverDir.getFileName().toString();
                           LOG.infof("Found server directory: %s", serverId);

                           // Skip if already loaded (first one wins)
                           if (configs.containsKey(serverId)) {
                               LOG.debugf("Skipping duplicate server: %s", serverId);
                               return;
                           }

                           // Load using appropriate loader — legacy path (will be removed)
                           ServerDescriptorLoaderBase<?> loader = loaders.get(root);
                           if (loader != null) {
                               try {
                                   ServerConfigBase config = loader.load(serverDir, null);
                                   configs.put(serverId, config);
                                   LOG.infof("Loaded server: %s from %s", serverId, root);
                               } catch (Exception e) {
                                   LOG.errorf(e, "Failed to load server %s", serverId);
                               }
                           } else {
                               LOG.warnf("No loader found for root: %s", root);
                           }
                       });
            }

        } catch (Exception e) {
            LOG.warnf(e, "Failed to scan directory: %s", dirUrl);
        }
    }
}
