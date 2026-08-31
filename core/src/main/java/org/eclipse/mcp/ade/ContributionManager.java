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
package org.eclipse.mcp.ade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.server.ServerConfigBase;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class ContributionManager {

    private static final Logger LOG = Logger.getLogger(ContributionManager.class);

    private final Application application;
    private final PathManager pathManager;
    private final ConcurrentHashMap<String, List<String>> resolvedContributionsCache = new ConcurrentHashMap<>();

    ContributionManager(Application application) {
        this.application = application;
        this.pathManager = application.getPathManager();
    }

    private static String cacheKey(String contributeServerId, String contributionName) {
        return contributeServerId + ":" + contributionName;
    }

    public void invalidateContributionCache(String contributeServerId, String contributionName) {
        resolvedContributionsCache.remove(cacheKey(contributeServerId, contributionName));
    }

    public void invalidateAllCaches() {
        resolvedContributionsCache.clear();
    }

    public static class ContributionResult {
        private final List<String> resolvedFiles;
        private final List<ServerConfigBase> uninstalledContributors;

        ContributionResult(List<String> resolvedFiles, List<ServerConfigBase> uninstalledContributors) {
            this.resolvedFiles = resolvedFiles;
            this.uninstalledContributors = uninstalledContributors;
        }

        public List<String> getResolvedFiles() {
            return resolvedFiles;
        }

        public List<ServerConfigBase> getUninstalledContributors() {
            return uninstalledContributors;
        }
    }

    public List<String> extractFilesFromContribution(String contributeServerId, String contributionName) {
        String key = cacheKey(contributeServerId, contributionName);
        List<String> cached = resolvedContributionsCache.get(key);
        if (cached != null) {
            return cached;
        }
        return extractFilesFromContributionWithStatus(contributeServerId, contributionName).getResolvedFiles();
    }

    public ContributionResult extractFilesFromContributionWithStatus(String contributeServerId, String contributionName) {
        String key = cacheKey(contributeServerId, contributionName);
        List<String> cached = resolvedContributionsCache.get(key);
        if (cached != null) {
            return new ContributionResult(cached, List.of());
        }

        List<String> resolvedFiles = new ArrayList<>();
        List<ServerConfigBase> uninstalledContributors = new ArrayList<>();
        for (var serverConfig : application.getLspServerConfigs()) {
            extractFilesFromContribution(contributeServerId, contributionName, serverConfig, resolvedFiles, uninstalledContributors);
        }
        for (var serverConfig : application.getDapServerConfigs()) {
            extractFilesFromContribution(contributeServerId, contributionName, serverConfig, resolvedFiles, uninstalledContributors);
        }

        if (uninstalledContributors.isEmpty() && !resolvedFiles.isEmpty()) {
            resolvedContributionsCache.put(key, List.copyOf(resolvedFiles));
        }

        return new ContributionResult(resolvedFiles, uninstalledContributors);
    }

    private void extractFilesFromContribution(String contributeServerId,
                                              String contributionName,
                                              ServerConfigBase serverConfig,
                                              List<String> resolvedFiles,
                                              List<ServerConfigBase> uninstalledContributors) {
        if (serverConfig.getContributes() == null) {
            return;
        }

        JsonElement contribution = serverConfig.getContributes().getContribution(contributeServerId);
        if (contribution == null || !contribution.isJsonObject()) {
            return;
        }

        JsonObject contributeObj = contribution.getAsJsonObject();
        if (!contributeObj.has(contributionName) || !contributeObj.get(contributionName).isJsonArray()) {
            return;
        }

        // Extract and resolve bundle paths in one step
        JsonArray bundles = contributeObj.getAsJsonArray(contributionName);
        List<String> configResolvedFiles = new ArrayList<>();
        for (JsonElement bundleElem : bundles) {
            String bundlePattern = bundleElem.getAsString();
            List<String> extractedPaths = extractAndResolve(serverConfig, bundlePattern);
            configResolvedFiles.addAll(extractedPaths);
        }

        if (!configResolvedFiles.isEmpty()) {
            resolvedFiles.addAll(configResolvedFiles);
        } else if (serverConfig.getInstaller() != null) {
            uninstalledContributors.add(serverConfig);
        }
    }

    /**
     * Extract files from resources to filesystem AND return their absolute paths.
     * This combines extraction + resolution in one step for efficiency.
     */
    private List<String> extractAndResolve(ServerConfigBase contributorConfig, String bundlePattern) {
        String normalizedPattern = bundlePattern.startsWith("./") ? bundlePattern.substring(2) : bundlePattern;
        extractBundleFromResources(contributorConfig, normalizedPattern);
        return resolveBundlesFromServerHome(contributorConfig, normalizedPattern);
    }

    /**
     * Resolve bundles from serverHome (installed directory).
     */
    private List<String> resolveBundlesFromServerHome(ServerConfigBase contributorConfig, String normalizedPath) {
        List<String> resolved = new ArrayList<>();

        Path contributorHome = contributorConfig.getServerHome();
        if (contributorHome == null || !Files.exists(contributorHome)) {
            LOG.debugf("Contributor server home not found: %s (looking for installed bundles)", contributorConfig.getServerId());
            return resolved;
        }

        // Check for wildcards
        if (normalizedPath.contains("*") || normalizedPath.contains("?")) {
            // Use Java glob pattern matching
            // Extract the directory to search in
            int lastSlash = normalizedPath.lastIndexOf('/');
            String dirPart = lastSlash >= 0 ? normalizedPath.substring(0, lastSlash) : "";
            String filePattern = lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;

            Path searchDir = dirPart.isEmpty() ? contributorHome : contributorHome.resolve(dirPart);

            // Build glob pattern for matching (just the filename part)
            String globPattern = "glob:" + filePattern;

            LOG.debugf("Expanding glob pattern: %s in directory: %s", globPattern, searchDir);

            try {
                java.nio.file.PathMatcher matcher = searchDir.getFileSystem().getPathMatcher(globPattern);

                if (Files.exists(searchDir) && Files.isDirectory(searchDir)) {
                    try (Stream<Path> files = Files.list(searchDir)) {
                        files.filter(p -> matcher.matches(p.getFileName()))
                                .forEach(p -> {
                                    resolved.add(p.toAbsolutePath().toString());
                                    LOG.infof("Resolved bundle: %s (from %s)", p, contributorConfig.getServerId());
                                });
                    }
                } else {
                    LOG.warnf("Bundle directory not found: %s", searchDir);
                }
            } catch (IOException e) {
                LOG.warnf("Failed to expand glob pattern %s: %s", normalizedPath, e.getMessage());
            }
        } else {
            // Simple path
            Path bundlePath = contributorHome.resolve(normalizedPath);
            if (Files.exists(bundlePath)) {
                resolved.add(bundlePath.toAbsolutePath().toString());
                LOG.debugf("Resolved bundle: %s (from %s)", bundlePath, contributorConfig.getServerId());
            } else {
                LOG.warnf("Bundle not found: %s (from server %s)", bundlePath, contributorConfig.getServerId());
            }
        }

        return resolved;
    }

    /**
     * Extract a bundle (or bundle directory) from resources to filesystem.
     */
    private void extractBundleFromResources(ServerConfigBase contributorConfig, String normalizedPath) {
        Path targetServerHome = contributorConfig.getServerHome();

        try {
            Files.createDirectories(targetServerHome);

            // Resource path (derived from serverHome to work for both LSP and DAP)
            String resourcePath = contributorConfig.getResourceBasePath() + "/" + normalizedPath;

            // Check if it's a directory pattern (e.g., "plugins/*.jar" -> extract "plugins/" directory)
            if (normalizedPath.contains("*")) {
                // Extract the directory part
                int lastSlash = normalizedPath.lastIndexOf('/');
                String dirPart = lastSlash >= 0 ? normalizedPath.substring(0, lastSlash) : "";
                if (!dirPart.isEmpty()) {
                    extractResourceDirectory(contributorConfig.getResourceBasePath() + "/" + dirPart, targetServerHome.resolve(dirPart));
                }
            } else if (normalizedPath.endsWith("/") || !normalizedPath.contains(".")) {
                // It's a directory
                extractResourceDirectory(resourcePath, targetServerHome.resolve(normalizedPath));
            } else {
                // It's a single file
                extractResourceFile(resourcePath, targetServerHome.resolve(normalizedPath));
            }

        } catch (IOException e) {
            LOG.warnf("Failed to extract bundle %s from resources: %s", normalizedPath, e.getMessage());
        }
    }

    /**
     * Extract a single file from resources to filesystem.
     */
    private void extractResourceFile(String resourcePath, Path targetPath) throws IOException {
        if (Files.exists(targetPath)) {
            return;
        }
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.debugf("Resource not found (might be in filesystem): %s", resourcePath);
                return;
            }

            Files.createDirectories(targetPath.getParent());
            Files.copy(is, targetPath, StandardCopyOption.REPLACE_EXISTING);
            LOG.infof("Extracted resource: %s -> %s", resourcePath, targetPath);
        }
    }

    /**
     * Extract a directory from resources to filesystem.
     */
    private void extractResourceDirectory(String resourceDirPath, Path targetDir) throws IOException {
        if (Files.exists(targetDir)) {
            try (Stream<Path> entries = Files.list(targetDir)) {
                if (entries.findAny().isPresent()) {
                    return;
                }
            }
        }

        LOG.infof("Extracting resource directory: %s -> %s", resourceDirPath, targetDir);

        Files.createDirectories(targetDir);

        // List resources in this directory
        // This is tricky because we need to handle both filesystem and JAR
        java.net.URL dirUrl = getClass().getResource(resourceDirPath);
        if (dirUrl == null) {
            LOG.debugf("Resource directory not found: %s", resourceDirPath);
            return;
        }

        try {
            if (dirUrl.toURI().getScheme().equals("jar")) {
                // Running from JAR
                extractFromJar(resourceDirPath, targetDir);
            } else {
                // Running from filesystem (IDE)
                Path sourcePath = Paths.get(dirUrl.toURI());
                copyDirectory(sourcePath, targetDir);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to extract resource directory %s: %s", resourceDirPath, e.getMessage());
        }
    }

    /**
     * Extract files from JAR resources.
     */
    private void extractFromJar(String resourcePath, Path targetDir) throws IOException {
        // Use the JAR file system to list and copy files
        java.net.URL jarUrl = getClass().getProtectionDomain().getCodeSource().getLocation();
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(new java.io.File(jarUrl.toURI()))) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
            String prefix = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
            if (!prefix.endsWith("/")) {
                prefix += "/";
            }

            while (entries.hasMoreElements()) {
                java.util.jar.JarEntry entry = entries.nextElement();
                if (entry.getName().startsWith(prefix) && !entry.isDirectory()) {
                    String relativePath = entry.getName().substring(prefix.length());
                    Path targetFile = targetDir.resolve(relativePath);

                    Files.createDirectories(targetFile.getParent());
                    try (InputStream is = jarFile.getInputStream(entry)) {
                        Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        LOG.debugf("Extracted: %s", targetFile);
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to extract from JAR: %s", e.getMessage());
        }
    }

    /**
     * Copy directory recursively (for filesystem mode).
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        LOG.debugf("Copied: %s", dest);
                    }
                } catch (IOException e) {
                    LOG.warnf("Failed to copy %s: %s", src, e.getMessage());
                }
            });
        }
    }
}

