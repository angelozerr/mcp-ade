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
package org.eclipse.mcp.ade.extensions.jdtls.build;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.mcp.ade.Application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Manages a disk-based cache for {@link ClasspathInfo} extracted in fast mode.
 *
 * <p>On first extraction, the classpath is persisted as JSON under
 * {@code ~/.mcp-languagetools/classpath-cache/{workspace}/}. On subsequent
 * starts, if the relevant POM files have not changed (by last-modified timestamp),
 * the cached classpath is reused — skipping Maven entirely.</p>
 */
@ApplicationScoped
public class ClasspathCacheManager {

    private static final Logger LOG = Logger.getLogger(ClasspathCacheManager.class);

    private static final String CACHE_DIR_NAME = "classpath-cache";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Inject
    Application application;

    /**
     * Loads the cached {@link ClasspathInfo} for a module if the cache is still valid.
     *
     * <p>Validation checks, applied in order:
     * <ol>
     *   <li>Cache file must exist on disk</li>
     *   <li>All POM files tracked in the cache must still exist with the same
     *       last-modified timestamp</li>
     *   <li>Non-JAR entries (e.g., {@code .pom} files from BOM dependencies)
     *       are filtered out for backward compatibility</li>
     *   <li>All cached JAR files must still exist on disk (guards against
     *       {@code ~/.m2/repository} cleanup)</li>
     * </ol>
     *
     * <p>If any check fails, {@code Optional.empty()} is returned and the caller
     * should re-extract the classpath from the build tool.</p>
     *
     * @param workspaceRoot the workspace root directory
     * @param moduleDir     the module directory
     * @return the cached classpath info if valid, or empty if the cache is stale/missing
     */
    public Optional<ClasspathInfo> loadIfValid(Path workspaceRoot, Path moduleDir) {
        Path cacheFile = getCacheFile(workspaceRoot, moduleDir);
        if (!Files.exists(cacheFile)) {
            return Optional.empty();
        }

        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            CacheEntry entry = GSON.fromJson(reader, CacheEntry.class);
            if (entry == null || entry.classpathInfo == null || entry.pomTimestamps == null) {
                LOG.debugf("Invalid cache file: %s", cacheFile);
                return Optional.empty();
            }

            for (Map.Entry<String, Long> pomEntry : entry.pomTimestamps.entrySet()) {
                Path pomPath = Path.of(pomEntry.getKey());
                if (!Files.exists(pomPath)) {
                    LOG.infof("Cache invalidated: POM deleted %s", pomPath);
                    return Optional.empty();
                }
                long currentTimestamp = Files.getLastModifiedTime(pomPath).toMillis();
                if (currentTimestamp != pomEntry.getValue()) {
                    LOG.infof("Cache invalidated: POM changed %s", pomPath);
                    return Optional.empty();
                }
            }

            // Filter out non-JAR entries (e.g. .pom files) that may exist in older caches
            ClasspathInfo info = entry.classpathInfo;
            List<String> filteredJars = info.classpathJars().stream()
                    .filter(jar -> jar.endsWith(".jar"))
                    .toList();
            if (filteredJars.size() != info.classpathJars().size()) {
                info = new ClasspathInfo(info.moduleName(), info.projectPath(),
                        info.sourceRoots(), filteredJars, info.reactorModuleDeps(),
                        info.buildFiles() != null ? info.buildFiles() : List.of());
            }

            // Verify that all cached JARs still exist on disk
            // (e.g. ~/.m2/repository may have been cleaned)
            List<String> missingJars = info.classpathJars().stream()
                    .filter(jar -> !Files.exists(Path.of(jar)))
                    .toList();
            if (!missingJars.isEmpty()) {
                LOG.infof("Cache invalidated: %d JAR(s) no longer exist on disk (first: %s)",
                        missingJars.size(), missingJars.get(0));
                return Optional.empty();
            }

            LOG.infof("Using cached classpath for module %s (%d JARs)",
                    info.moduleName(), info.classpathJars().size());
            return Optional.of(info);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to read classpath cache: %s", cacheFile);
            return Optional.empty();
        }
    }

    /**
     * Saves the extracted {@link ClasspathInfo} to disk along with the current
     * build file timestamps for cache invalidation.
     *
     * <p>The cache file is a JSON document containing the full {@link ClasspathInfo}
     * record and a map of build file paths to their last-modified timestamps.
     * On the next load, timestamps are compared to detect changes.</p>
     *
     * @param workspaceRoot the workspace root directory (used for cache file path derivation)
     * @param moduleDir     the module directory (used for cache file naming)
     * @param info          the classpath information to persist
     */
    public void save(Path workspaceRoot, Path moduleDir, ClasspathInfo info) {
        Path cacheFile = getCacheFile(workspaceRoot, moduleDir);
        try {
            Files.createDirectories(cacheFile.getParent());

            Map<String, Long> buildFileTimestamps = collectBuildFileTimestamps(info);

            CacheEntry entry = new CacheEntry();
            entry.pomTimestamps = buildFileTimestamps;
            entry.classpathInfo = info;

            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(entry, writer);
            }

            LOG.infof("Classpath cache saved for module %s (%d JARs, %d build files tracked)",
                    info.moduleName(), info.classpathJars().size(), buildFileTimestamps.size());
        } catch (IOException e) {
            LOG.warnf(e, "Failed to save classpath cache: %s", cacheFile);
        }
    }

    /**
     * Collects the current last-modified timestamps for all build files tracked
     * in the given {@link ClasspathInfo}.
     *
     * <p>Files that no longer exist are silently skipped (they will cause
     * cache invalidation on the next {@link #loadIfValid} call).</p>
     *
     * @param info the classpath info whose {@link ClasspathInfo#buildFiles()} to inspect
     * @return an ordered map of build file paths to their last-modified timestamps (millis)
     * @throws IOException if reading a file's timestamp fails
     */
    private Map<String, Long> collectBuildFileTimestamps(ClasspathInfo info) throws IOException {
        Map<String, Long> timestamps = new LinkedHashMap<>();
        if (info.buildFiles() != null) {
            for (String buildFile : info.buildFiles()) {
                Path path = Path.of(buildFile);
                if (Files.exists(path)) {
                    timestamps.put(buildFile, Files.getLastModifiedTime(path).toMillis());
                }
            }
        }
        return timestamps;
    }

    /**
     * Loads all valid cached {@link ClasspathInfo} entries for a workspace.
     *
     * @param workspaceRoot the workspace root directory
     * @return list of valid cached classpath info entries
     */
    public List<ClasspathInfo> loadAllValid(Path workspaceRoot) {
        Path cacheDir = getCacheDir(workspaceRoot);
        List<ClasspathInfo> results = new ArrayList<>();
        if (!Files.isDirectory(cacheDir)) {
            return results;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.json")) {
            for (Path cacheFile : stream) {
                try (Reader reader = Files.newBufferedReader(cacheFile)) {
                    CacheEntry entry = GSON.fromJson(reader, CacheEntry.class);
                    if (entry == null || entry.classpathInfo == null || entry.pomTimestamps == null) {
                        continue;
                    }
                    boolean valid = true;
                    for (Map.Entry<String, Long> pomEntry : entry.pomTimestamps.entrySet()) {
                        Path pomPath = Path.of(pomEntry.getKey());
                        if (!Files.exists(pomPath)) {
                            valid = false;
                            break;
                        }
                        long currentTimestamp = Files.getLastModifiedTime(pomPath).toMillis();
                        if (currentTimestamp != pomEntry.getValue()) {
                            valid = false;
                            break;
                        }
                    }
                    if (!valid) {
                        continue;
                    }
                    ClasspathInfo info = entry.classpathInfo;
                    List<String> filteredJars = info.classpathJars().stream()
                            .filter(jar -> jar.endsWith(".jar"))
                            .toList();
                    if (filteredJars.size() != info.classpathJars().size()) {
                        info = new ClasspathInfo(info.moduleName(), info.projectPath(),
                                info.sourceRoots(), filteredJars, info.reactorModuleDeps(),
                                info.buildFiles() != null ? info.buildFiles() : List.of());
                    }
                    List<String> missingJars = info.classpathJars().stream()
                            .filter(jar -> !Files.exists(Path.of(jar)))
                            .toList();
                    if (!missingJars.isEmpty()) {
                        continue;
                    }
                    results.add(info);
                } catch (Exception e) {
                    LOG.debugf(e, "Failed to read cache file: %s", cacheFile);
                }
            }
        } catch (IOException e) {
            LOG.debugf(e, "Failed to scan cache dir: %s", cacheDir);
        }
        return results;
    }

    private Path getCacheFile(Path workspaceRoot, Path moduleDir) {
        Path cacheDir = getCacheDir(workspaceRoot);
        Path normalizedRoot = workspaceRoot.toAbsolutePath().normalize();
        Path normalizedModule = moduleDir.toAbsolutePath().normalize();
        String relativePath = normalizedRoot.relativize(normalizedModule).toString();
        String safeName = relativePath.replace('\\', '-').replace('/', '-');
        if (safeName.isEmpty()) {
            safeName = "_root";
        }
        return cacheDir.resolve(safeName + ".json");
    }

    private Path getCacheDir(Path workspaceRoot) {
        String workspaceName = workspaceRoot.getFileName().toString();
        int hash = workspaceRoot.toAbsolutePath().normalize().toUri().hashCode() & 0x7FFFFFFF;
        String dirName = workspaceName + "-" + hash;
        return application.getPathManager().getMcpAdeRoot()
                .resolve(CACHE_DIR_NAME)
                .resolve(dirName);
    }

    private static class CacheEntry {
        Map<String, Long> pomTimestamps;
        ClasspathInfo classpathInfo;
    }
}
