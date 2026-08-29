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
import com.ibm.mcp.languagetools.PathManager;
import com.ibm.mcp.languagetools.bsp.server.BspServerConfig;
import com.ibm.mcp.languagetools.configuration.ApplicationConfiguration;
import com.ibm.mcp.languagetools.configuration.PathConfig;
import com.ibm.mcp.languagetools.dap.server.DapServerConfig;
import com.ibm.mcp.languagetools.installer.InstallerEvent;
import com.ibm.mcp.languagetools.installer.InstallerListener;
import com.ibm.mcp.languagetools.installer.InstallResult;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import com.ibm.mcp.languagetools.runtime.RuntimeDescriptorLoader;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.server.ServerDescriptorRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Central registry for all extensions. Manages the lifecycle of extensions
 * and their LSP/DAP server configs: add, remove, enable, disable.
 */
@ApplicationScoped
public class ExtensionRegistry {

    private static final Logger LOG = Logger.getLogger(ExtensionRegistry.class);

    @Inject
    PathManager pathManager;

    @Inject
    ServerDescriptorRegistry serverDescriptorRegistry;

    @Inject
    ApplicationConfiguration applicationConfiguration;

    private final Map<String, Extension> extensions = new ConcurrentHashMap<>();
    private final Set<String> disabledExtensions = ConcurrentHashMap.newKeySet();
    private final Set<String> disabledServers = ConcurrentHashMap.newKeySet();

    private final List<ExtensionListener> extensionListeners = new CopyOnWriteArrayList<>();
    private final List<InstallerListener> installerListeners = new CopyOnWriteArrayList<>();

    // ========== Listeners ==========

    public void addExtensionListener(ExtensionListener listener) {
        extensionListeners.add(listener);
    }

    public void removeExtensionListener(ExtensionListener listener) {
        extensionListeners.remove(listener);
    }

    public void addInstallerListener(InstallerListener listener) {
        installerListeners.add(listener);
    }

    public void removeInstallerListener(InstallerListener listener) {
        installerListeners.remove(listener);
    }

    public void fireOnInstalled(ServerConfigBase config, InstallResult result) {
        var event = new InstallerEvent(config, result);
        for (InstallerListener listener : installerListeners) {
            try {
                listener.onInstalled(event);
            } catch (Exception e) {
                LOG.warnf(e, "InstallerListener.onInstalled failed for '%s'", config.getServerId());
            }
        }
    }

    // ========== Startup: deploy bundled + scan ==========

    /**
     * Deploy bundled server configs from classpath to extensions/ directory,
     * then scan extensions/ to load all configs.
     */
    private final Set<String> bundledExtensionIds = ConcurrentHashMap.newKeySet();

    public void initialize(Application application) {
        deployBundledConfigs(application);
        scanExtensions(application);
        LOG.infof("ExtensionRegistry initialized: %d extensions, %d LSP servers, %d DAP servers, %d BSP servers",
                extensions.size(),
                getAllLspServerConfigs().size(),
                getAllDapServerConfigs().size(),
                getAllBspServerConfigs().size());
    }

    private static final String MCP_EXTENSION_JSON = "mcp-extension.json";

    /**
     * Deploy bundled configs from classpath to extensions/ directory.
     * Discovers extensions via mcp-extension.json descriptors, then scans
     * their lsp/ and dap/ subdirectories for server configs.
     * Overwrites configs but NOT binaries (bin/ directories).
     */
    private void deployBundledConfigs(Application application) {
        try {
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> descriptors = classLoader.getResources(MCP_EXTENSION_JSON);

            while (descriptors.hasMoreElements()) {
                URL descriptorUrl = descriptors.nextElement();
                deployBundledExtension(descriptorUrl);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to deploy bundled configs");
        }
    }

    private final Map<String, String> bundledExtensionNames = new ConcurrentHashMap<>();

    private void deployBundledExtension(URL descriptorUrl) {
        try {
            ExtensionDescriptor descriptor = readExtensionDescriptor(descriptorUrl);
            String extensionId = descriptor.id();
            if (extensionId == null || extensionId.isBlank()) {
                LOG.warnf("mcp-extension.json has no 'id' field: %s", descriptorUrl);
                return;
            }

            bundledExtensionIds.add(extensionId);
            if (descriptor.name() != null) {
                bundledExtensionNames.put(extensionId, descriptor.name());
            }
            Path basePath = resolveBasePath(descriptorUrl);

            for (String root : List.of(RuntimeDescriptorLoader.ROOT, PathConfig.getLspDirName(), PathConfig.getDapDirName(), PathConfig.getBspDirName())) {
                Path rootPath = basePath.resolve(root);
                if (!Files.isDirectory(rootPath)) {
                    continue;
                }
                try (Stream<Path> entries = Files.list(rootPath)) {
                    entries.filter(Files::isDirectory)
                           .forEach(serverDir -> {
                               String serverId = serverDir.getFileName().toString();
                               Path targetDir = pathManager.getExtensionServerHome(extensionId, root, serverId);
                               deployServerDir(serverDir, targetDir);
                           });
                }
            }

            LOG.debugf("Deployed bundled extension '%s' from %s", extensionId, descriptorUrl);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to deploy bundled extension from %s", descriptorUrl);
        }
    }

    private record ExtensionDescriptor(String id, String name) {}

    private ExtensionDescriptor readExtensionDescriptor(URL descriptorUrl) throws IOException {
        try (InputStream is = descriptorUrl.openStream();
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            String id = json.has("id") ? json.get("id").getAsString() : null;
            String name = json.has("name") ? json.get("name").getAsString() : null;
            return new ExtensionDescriptor(id, name);
        }
    }

    private String readExtensionNameFromDir(Path extensionDir) {
        Path descriptor = extensionDir.resolve(MCP_EXTENSION_JSON);
        if (!Files.exists(descriptor)) {
            return null;
        }
        try (InputStream is = Files.newInputStream(descriptor);
             InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return json.has("name") ? json.get("name").getAsString() : null;
        } catch (IOException e) {
            LOG.warnf(e, "Failed to read extension name from %s", descriptor);
            return null;
        }
    }

    private Path resolveBasePath(URL descriptorUrl) throws Exception {
        URI uri = descriptorUrl.toURI();
        if ("jar".equals(uri.getScheme())) {
            FileSystem fs;
            try {
                fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
            } catch (FileSystemAlreadyExistsException e) {
                fs = FileSystems.getFileSystem(uri);
            }
            Path descriptorPath = fs.getPath("/" + MCP_EXTENSION_JSON);
            return descriptorPath.getParent();
        } else {
            Path descriptorPath = Paths.get(uri);
            return descriptorPath.getParent();
        }
    }

    private void deployServerDir(Path sourceDir, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            try (Stream<Path> files = Files.list(sourceDir)) {
                files.forEach(source -> {
                    try {
                        String fileName = source.getFileName().toString();
                        Path target = targetDir.resolve(fileName);
                        if (Files.isDirectory(source)) {
                            copyDirectoryRecursively(source, target);
                        } else {
                            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        LOG.warnf(e, "Failed to deploy file %s", source);
                    }
                });
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to deploy config from %s to %s", sourceDir, targetDir);
        }
    }

    private void copyDirectoryRecursively(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> entries = Files.list(source)) {
            entries.forEach(entry -> {
                try {
                    Path dest = target.resolve(entry.getFileName().toString());
                    if (Files.isDirectory(entry)) {
                        copyDirectoryRecursively(entry, dest);
                    } else {
                        Files.copy(entry, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    LOG.warnf(e, "Failed to copy %s", entry);
                }
            });
        }
    }

    /**
     * Scan extensions/ directory and load all extension configs.
     */
    private void scanExtensions(Application application) {
        Path extensionsDir = pathManager.getExtensionsDir();
        if (!Files.isDirectory(extensionsDir)) {
            LOG.infof("Extensions directory does not exist: %s", extensionsDir);
            return;
        }

        try (var extensionDirs = Files.list(extensionsDir)) {
            extensionDirs.filter(Files::isDirectory)
                    .forEach(extDir -> {
                        String extensionId = extDir.getFileName().toString();
                        ServerConfigSource source = bundledExtensionIds.contains(extensionId)
                                ? ServerConfigSource.BUNDLED
                                : ServerConfigSource.USER;
                        try {
                            loadExtension(extensionId, source, application);
                        } catch (Exception e) {
                            LOG.errorf(e, "Failed to load extension: %s", extensionId);
                        }
                    });
        } catch (Exception e) {
            LOG.errorf(e, "Failed to scan extensions directory: %s", extensionsDir);
        }
    }

    /**
     * Load an extension from its directory on the filesystem.
     */
    private Extension loadExtension(String extensionId, ServerConfigSource source, Application application) {
        Extension extension = new Extension(extensionId, source, application);
        String extensionName = bundledExtensionNames.get(extensionId);
        if (extensionName == null) {
            extensionName = readExtensionNameFromDir(pathManager.getExtensionDir(extensionId));
        }
        if (extensionName != null) {
            extension.setName(extensionName);
        }
        Path extensionDir = pathManager.getExtensionDir(extensionId);

        Map<String, ServerConfigBase> configs = serverDescriptorRegistry.loadFromExtensionDir(extensionDir, extension);

        for (ServerConfigBase config : configs.values()) {
            if (config instanceof LspServerConfig lspConfig) {
                extension.addLspServerConfig(lspConfig);
            } else if (config instanceof DapServerConfig dapConfig) {
                extension.addDapServerConfig(dapConfig);
            } else if (config instanceof BspServerConfig bspConfig) {
                extension.addBspServerConfig(bspConfig);
            }
        }

        extensions.put(extensionId, extension);

        if (isExtensionEnabled(extensionId)) {
            fireOnAdded(extension);
        }

        return extension;
    }

    // ========== Add extension ==========

    /**
     * Add an extension from a source path (folder, ZIP, or JAR).
     */
    public Extension addExtension(String extensionId, Path source, Application application) throws IOException {
        if (extensions.containsKey(extensionId)) {
            throw new IllegalStateException("Extension '" + extensionId + "' is already deployed");
        }

        Path targetDir = pathManager.getExtensionDir(extensionId);
        ExtensionExtractor.extract(source, targetDir);

        // Validate no duplicate serverIds before registering
        Extension extension = loadExtension(extensionId, ServerConfigSource.USER, application);
        try {
            for (LspServerConfig config : extension.getLspServerConfigs()) {
                checkServerIdUnique(config.getServerId(), extensionId);
            }
            for (DapServerConfig config : extension.getDapServerConfigs()) {
                checkServerIdUnique(config.getServerId(), extensionId);
            }
            for (BspServerConfig config : extension.getBspServerConfigs()) {
                checkServerIdUnique(config.getServerId(), extensionId);
            }
        } catch (Exception e) {
            extensions.remove(extensionId);
            throw e;
        }

        return extension;
    }

    // ========== Add server from JSON content ==========

    /**
     * Add a server from inline JSON content (server.json body).
     *
     * @param serverType  "lsp", "dap", or "bsp"
     * @param jsonContent the server.json content as a string
     * @param extensionId optional extension ID; defaults to the server's "id" field
     * @param application the application instance
     * @return the extension the server was added to
     */
    public Extension addServerFromJson(String serverType, String jsonContent, String extensionId,
                                       Application application) throws IOException {
        JsonObject json = JsonParser.parseString(jsonContent).getAsJsonObject();
        if (!json.has("id") || json.get("id").getAsString().isBlank()) {
            throw new IOException("server.json must contain an \"id\" field");
        }
        String serverId = json.get("id").getAsString();

        if (extensionId == null || extensionId.isBlank()) {
            extensionId = serverId;
        }

        checkServerIdUnique(serverId, extensionId);

        var loader = serverDescriptorRegistry.getLoader(serverType);
        if (loader == null) {
            throw new IOException("Unknown server type: " + serverType);
        }

        Path targetDir = pathManager.getExtensionServerHome(extensionId, serverType, serverId);
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("server.json"), jsonContent, java.nio.charset.StandardCharsets.UTF_8);

        Extension extension = getOrCreateExtension(extensionId, ServerConfigSource.USER, application);
        ServerConfigBase config = loader.load(targetDir, extension);

        if (config instanceof LspServerConfig lspConfig) {
            extension.addLspServerConfig(lspConfig);
        } else if (config instanceof DapServerConfig dapConfig) {
            extension.addDapServerConfig(dapConfig);
        } else if (config instanceof BspServerConfig bspConfig) {
            extension.addBspServerConfig(bspConfig);
        }

        fireOnAdded(extension);
        return extension;
    }

    // ========== Add individual servers ==========

    /**
     * Add an LSP server from a source path. extensionId defaults to serverId from the config.
     */
    public LspServerConfig addLspServer(Path source, Application application) throws IOException {
        return addLspServer(source, null, application);
    }

    /**
     * Add an LSP server from a source path into the given extension.
     */
    public LspServerConfig addLspServer(Path source, String extensionId, Application application) throws IOException {
        // Extract to temp to read server.json and get serverId
        Path tempDir = Files.createTempDirectory("mcp-lsp-");
        try {
            ExtensionExtractor.extract(source, tempDir);
            // The serverId is the directory name in the source, or the temp dir itself for flat sources
            String serverId = detectServerId(tempDir);

            if (extensionId == null) {
                extensionId = serverId;
            }

            checkServerIdUnique(serverId, extensionId);

            Path targetDir = pathManager.getExtensionServerHome(extensionId, "lsp", serverId);
            Files.createDirectories(targetDir.getParent());
            ExtensionExtractor.extract(source, targetDir);

            Extension extension = getOrCreateExtension(extensionId, ServerConfigSource.USER, application);
            var loader = serverDescriptorRegistry.getLoader("lsp");
            LspServerConfig config = (LspServerConfig) loader.load(targetDir, extension);
            extension.addLspServerConfig(config);

            fireOnAdded(extension);
            return config;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Add an LSP server from a pre-loaded config. extensionId defaults to config.serverId.
     */
    public void addLspServer(LspServerConfig config) {
        addLspServer(config, config.getServerId());
    }

    /**
     * Add an LSP server from a pre-loaded config into the given extension.
     */
    public void addLspServer(LspServerConfig config, String extensionId) {
        checkServerIdUnique(config.getServerId(), extensionId);
        Extension extension = getOrCreateExtension(extensionId, ServerConfigSource.USER, config.getApplication());
        extension.addLspServerConfig(config);
        fireOnAdded(extension);
    }

    /**
     * Add a DAP server from a source path. extensionId defaults to serverId from the config.
     */
    public DapServerConfig addDapServer(Path source, Application application) throws IOException {
        return addDapServer(source, null, application);
    }

    /**
     * Add a DAP server from a source path into the given extension.
     */
    public DapServerConfig addDapServer(Path source, String extensionId, Application application) throws IOException {
        Path tempDir = Files.createTempDirectory("mcp-dap-");
        try {
            ExtensionExtractor.extract(source, tempDir);
            String serverId = detectServerId(tempDir);

            if (extensionId == null) {
                extensionId = serverId;
            }

            checkServerIdUnique(serverId, extensionId);

            Path targetDir = pathManager.getExtensionServerHome(extensionId, "dap", serverId);
            Files.createDirectories(targetDir.getParent());
            ExtensionExtractor.extract(source, targetDir);

            Extension extension = getOrCreateExtension(extensionId, ServerConfigSource.USER, application);
            var loader = serverDescriptorRegistry.getLoader("dap");
            DapServerConfig config = (DapServerConfig) loader.load(targetDir, extension);
            extension.addDapServerConfig(config);

            fireOnAdded(extension);
            return config;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Add a DAP server from a pre-loaded config. extensionId defaults to config.serverId.
     */
    public void addDapServer(DapServerConfig config) {
        addDapServer(config, config.getServerId());
    }

    /**
     * Add a DAP server from a pre-loaded config into the given extension.
     */
    public void addDapServer(DapServerConfig config, String extensionId) {
        checkServerIdUnique(config.getServerId(), extensionId);
        Extension extension = getOrCreateExtension(extensionId, ServerConfigSource.USER, config.getApplication());
        extension.addDapServerConfig(config);
        fireOnAdded(extension);
    }

    /**
     * Add a BSP server from a source path. extensionId defaults to serverId from the config.
     */
    public BspServerConfig addBspServer(Path source, Application application) throws IOException {
        return addBspServer(source, null, application);
    }

    /**
     * Add a BSP server from a source path into the given extension.
     */
    public BspServerConfig addBspServer(Path source, String extensionId, Application application) throws IOException {
        Path tempDir = Files.createTempDirectory("mcp-bsp-");
        try {
            ExtensionExtractor.extract(source, tempDir);
            String serverId = detectServerId(tempDir);

            if (extensionId == null) {
                extensionId = serverId;
            }

            checkServerIdUnique(serverId, extensionId);

            Path targetDir = pathManager.getExtensionServerHome(extensionId, "bsp", serverId);
            Files.createDirectories(targetDir.getParent());
            ExtensionExtractor.extract(source, targetDir);

            Extension extension = getOrCreateExtension(extensionId, ServerConfigSource.USER, application);
            var loader = serverDescriptorRegistry.getLoader("bsp");
            BspServerConfig config = (BspServerConfig) loader.load(targetDir, extension);
            extension.addBspServerConfig(config);

            fireOnAdded(extension);
            return config;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    /**
     * Add a BSP server from a pre-loaded config. extensionId defaults to config.serverId.
     */
    public void addBspServer(BspServerConfig config) {
        addBspServer(config, config.getServerId());
    }

    /**
     * Add a BSP server from a pre-loaded config into the given extension.
     */
    public void addBspServer(BspServerConfig config, String extensionId) {
        checkServerIdUnique(config.getServerId(), extensionId);
        Extension extension = getOrCreateExtension(extensionId, ServerConfigSource.USER, config.getApplication());
        extension.addBspServerConfig(config);
        fireOnAdded(extension);
    }

    // ========== Remove ==========

    /**
     * Remove an entire extension (all its servers).
     */
    public void removeExtension(String extensionId) {
        Extension extension = extensions.get(extensionId);
        if (extension == null) {
            throw new IllegalArgumentException("Extension '" + extensionId + "' not found");
        }
        if (extension.getSource() == ServerConfigSource.BUNDLED) {
            throw new IllegalStateException("Cannot remove bundled extension '" + extensionId + "', use disable instead");
        }

        extensions.remove(extensionId);
        fireOnRemoved(extension);

        Path extensionDir = pathManager.getExtensionDir(extensionId);
        deleteRecursively(extensionDir);
    }

    /**
     * Remove an individual LSP server from its extension.
     */
    public void removeLspServer(String serverId) {
        Extension extension = findExtensionForLspServer(serverId);
        if (extension == null) {
            throw new IllegalArgumentException("LSP server '" + serverId + "' not found");
        }
        if (extension.getSource() == ServerConfigSource.BUNDLED) {
            throw new IllegalStateException("Cannot remove bundled server '" + serverId + "', use disable instead");
        }

        extension.removeLspServerConfig(serverId);

        Path serverDir = pathManager.getExtensionServerHome(extension.getId(), "lsp", serverId);
        deleteRecursively(serverDir);

        if (extension.isEmpty()) {
            extensions.remove(extension.getId());
            fireOnRemoved(extension);
            deleteRecursively(pathManager.getExtensionDir(extension.getId()));
        }
    }

    /**
     * Remove an individual DAP server from its extension.
     */
    public void removeDapServer(String serverId) {
        Extension extension = findExtensionForDapServer(serverId);
        if (extension == null) {
            throw new IllegalArgumentException("DAP server '" + serverId + "' not found");
        }
        if (extension.getSource() == ServerConfigSource.BUNDLED) {
            throw new IllegalStateException("Cannot remove bundled server '" + serverId + "', use disable instead");
        }

        extension.removeDapServerConfig(serverId);

        Path serverDir = pathManager.getExtensionServerHome(extension.getId(), "dap", serverId);
        deleteRecursively(serverDir);

        if (extension.isEmpty()) {
            extensions.remove(extension.getId());
            fireOnRemoved(extension);
            deleteRecursively(pathManager.getExtensionDir(extension.getId()));
        }
    }

    /**
     * Remove an individual BSP server from its extension.
     */
    public void removeBspServer(String serverId) {
        Extension extension = findExtensionForBspServer(serverId);
        if (extension == null) {
            throw new IllegalArgumentException("BSP server '" + serverId + "' not found");
        }
        if (extension.getSource() == ServerConfigSource.BUNDLED) {
            throw new IllegalStateException("Cannot remove bundled server '" + serverId + "', use disable instead");
        }

        extension.removeBspServerConfig(serverId);

        Path serverDir = pathManager.getExtensionServerHome(extension.getId(), "bsp", serverId);
        deleteRecursively(serverDir);

        if (extension.isEmpty()) {
            extensions.remove(extension.getId());
            fireOnRemoved(extension);
            deleteRecursively(pathManager.getExtensionDir(extension.getId()));
        }
    }

    // ========== Enable / Disable ==========

    public void enableExtension(String extensionId) {
        if (!extensions.containsKey(extensionId)) {
            throw new IllegalArgumentException("Extension '" + extensionId + "' not found");
        }
        disabledExtensions.remove(extensionId);
        persistDisabledExtensions();
        fireOnAdded(extensions.get(extensionId));
    }

    public void disableExtension(String extensionId) {
        Extension extension = extensions.get(extensionId);
        if (extension == null) {
            throw new IllegalArgumentException("Extension '" + extensionId + "' not found");
        }
        disabledExtensions.add(extensionId);
        persistDisabledExtensions();
        fireOnRemoved(extension);
    }

    public boolean isExtensionEnabled(String extensionId) {
        return !disabledExtensions.contains(extensionId);
    }

    public void enableServer(String serverId) {
        disabledServers.remove(serverId);
        persistDisabledServers();
    }

    public void disableServer(String serverId) {
        disabledServers.add(serverId);
        persistDisabledServers();
    }

    public boolean isServerEnabled(String serverId) {
        return !disabledServers.contains(serverId);
    }

    /**
     * Check if a server config is enabled (both its extension and the server itself).
     */
    public boolean isServerConfigEnabled(ServerConfigBase config) {
        String extensionId = config.getExtensionId();
        if (extensionId != null && !isExtensionEnabled(extensionId)) {
            return false;
        }
        return isServerEnabled(config.getServerId());
    }

    // ========== Disabled state persistence ==========

    public Set<String> getDisabledExtensions() {
        return Collections.unmodifiableSet(disabledExtensions);
    }

    public Set<String> getDisabledServers() {
        return Collections.unmodifiableSet(disabledServers);
    }

    public void setDisabledExtensions(Collection<String> disabled) {
        disabledExtensions.clear();
        disabledExtensions.addAll(disabled);
    }

    public void setDisabledServers(Collection<String> disabled) {
        disabledServers.clear();
        disabledServers.addAll(disabled);
    }

    // ========== Queries ==========

    public Extension getExtension(String extensionId) {
        return extensions.get(extensionId);
    }

    public Collection<Extension> getExtensions() {
        return Collections.unmodifiableCollection(extensions.values());
    }

    /**
     * All LSP server configs (enabled + disabled) — for admin, listing.
     */
    public Collection<LspServerConfig> getAllLspServerConfigs() {
        return extensions.values().stream()
                .flatMap(ext -> ext.getLspServerConfigs().stream())
                .toList();
    }

    /**
     * Only enabled LSP server configs — for ensureServerForFile().
     */
    public Collection<LspServerConfig> getEnabledLspServerConfigs() {
        return extensions.values().stream()
                .flatMap(ext -> ext.getLspServerConfigs().stream())
                .filter(this::isServerConfigEnabled)
                .toList();
    }

    /**
     * All DAP server configs (enabled + disabled) — for admin, listing.
     */
    public Collection<DapServerConfig> getAllDapServerConfigs() {
        return extensions.values().stream()
                .flatMap(ext -> ext.getDapServerConfigs().stream())
                .toList();
    }

    /**
     * Only enabled DAP server configs — for workspace matching.
     */
    public Collection<DapServerConfig> getEnabledDapServerConfigs() {
        return extensions.values().stream()
                .flatMap(ext -> ext.getDapServerConfigs().stream())
                .filter(this::isServerConfigEnabled)
                .toList();
    }

    public LspServerConfig getLspServerConfig(String serverId) {
        for (Extension ext : extensions.values()) {
            LspServerConfig config = ext.getLspServerConfig(serverId);
            if (config != null) {
                return config;
            }
        }
        return null;
    }

    public DapServerConfig getDapServerConfig(String serverId) {
        for (Extension ext : extensions.values()) {
            DapServerConfig config = ext.getDapServerConfig(serverId);
            if (config != null) {
                return config;
            }
        }
        return null;
    }

    /**
     * All BSP server configs (enabled + disabled) — for admin, listing.
     */
    public Collection<BspServerConfig> getAllBspServerConfigs() {
        return extensions.values().stream()
                .flatMap(ext -> ext.getBspServerConfigs().stream())
                .toList();
    }

    /**
     * Only enabled BSP server configs — for workspace matching.
     */
    public Collection<BspServerConfig> getEnabledBspServerConfigs() {
        return extensions.values().stream()
                .flatMap(ext -> ext.getBspServerConfigs().stream())
                .filter(this::isServerConfigEnabled)
                .toList();
    }

    public BspServerConfig getBspServerConfig(String serverId) {
        for (Extension ext : extensions.values()) {
            BspServerConfig config = ext.getBspServerConfig(serverId);
            if (config != null) {
                return config;
            }
        }
        return null;
    }

    // ========== Internal helpers ==========

    private Extension getOrCreateExtension(String extensionId, ServerConfigSource source, Application application) {
        return extensions.computeIfAbsent(extensionId, id -> new Extension(id, source, application));
    }

    private Extension findExtensionForLspServer(String serverId) {
        for (Extension ext : extensions.values()) {
            if (ext.getLspServerConfig(serverId) != null) {
                return ext;
            }
        }
        return null;
    }

    private Extension findExtensionForDapServer(String serverId) {
        for (Extension ext : extensions.values()) {
            if (ext.getDapServerConfig(serverId) != null) {
                return ext;
            }
        }
        return null;
    }

    private Extension findExtensionForBspServer(String serverId) {
        for (Extension ext : extensions.values()) {
            if (ext.getBspServerConfig(serverId) != null) {
                return ext;
            }
        }
        return null;
    }

    private void checkServerIdUnique(String serverId, String extensionId) {
        for (Extension ext : extensions.values()) {
            LspServerConfig lsp = ext.getLspServerConfig(serverId);
            if (lsp != null) {
                throw new IllegalStateException(
                        "Server '" + serverId + "' is already deployed in extension '" + ext.getId() + "'");
            }
            DapServerConfig dap = ext.getDapServerConfig(serverId);
            if (dap != null) {
                throw new IllegalStateException(
                        "Server '" + serverId + "' is already deployed in extension '" + ext.getId() + "'");
            }
            BspServerConfig bsp = ext.getBspServerConfig(serverId);
            if (bsp != null) {
                throw new IllegalStateException(
                        "Server '" + serverId + "' is already deployed in extension '" + ext.getId() + "'");
            }
        }
    }

    private String detectServerId(Path dir) throws IOException {
        // If the directory contains server.json directly, the serverId is the directory name
        if (Files.exists(dir.resolve("server.json"))) {
            return dir.getFileName().toString();
        }
        // Otherwise look for a single subdirectory containing server.json
        try (var entries = Files.list(dir)) {
            Optional<Path> subDir = entries.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("server.json")))
                    .findFirst();
            if (subDir.isPresent()) {
                return subDir.get().getFileName().toString();
            }
        }
        throw new IOException("Cannot detect serverId: no server.json found in " + dir);
    }

    private void fireOnAdded(Extension extension) {
        var event = new ExtensionAddedEvent(extension);
        for (ExtensionListener listener : extensionListeners) {
            try {
                listener.onAdded(event);
            } catch (Exception e) {
                LOG.warnf(e, "ExtensionListener.onAdded failed for '%s'", extension.getId());
            }
        }
    }

    private void fireOnRemoved(Extension extension) {
        var event = new ExtensionRemovedEvent(extension);
        for (ExtensionListener listener : extensionListeners) {
            try {
                listener.onRemoved(event);
            } catch (Exception e) {
                LOG.warnf(e, "ExtensionListener.onRemoved failed for '%s'", extension.getId());
            }
        }
    }

    private void persistDisabledExtensions() {
        applicationConfiguration.setDisabledExtensionIds(new ArrayList<>(disabledExtensions));
    }

    private void persistDisabledServers() {
        applicationConfiguration.setDisabledServerIds(new ArrayList<>(disabledServers));
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOG.warnf(e, "Failed to delete: %s", path);
        }
    }
}
