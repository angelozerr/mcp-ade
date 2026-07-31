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
package com.ibm.mcp.languagetools.extensions.jdtls.classpath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ibm.mcp.languagetools.Application;

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
     * Loads the cached {@link ClasspathInfo} for a module if the cache exists
     * and the POM timestamps haven't changed.
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
                        info.sourceRoots(), filteredJars, info.reactorModuleDeps());
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
     * POM timestamps for invalidation.
     */
    public void save(Path workspaceRoot, Path moduleDir, ClasspathInfo info) {
        Path cacheFile = getCacheFile(workspaceRoot, moduleDir);
        try {
            Files.createDirectories(cacheFile.getParent());

            Map<String, Long> pomTimestamps = collectPomTimestamps(workspaceRoot, moduleDir);

            CacheEntry entry = new CacheEntry();
            entry.pomTimestamps = pomTimestamps;
            entry.classpathInfo = info;

            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(entry, writer);
            }

            LOG.infof("Classpath cache saved for module %s (%d JARs)",
                    info.moduleName(), info.classpathJars().size());
        } catch (IOException e) {
            LOG.warnf(e, "Failed to save classpath cache: %s", cacheFile);
        }
    }

    private Map<String, Long> collectPomTimestamps(Path workspaceRoot, Path moduleDir) throws IOException {
        Map<String, Long> timestamps = new LinkedHashMap<>();

        Path rootPom = workspaceRoot.resolve("pom.xml");
        if (Files.exists(rootPom)) {
            timestamps.put(rootPom.toAbsolutePath().normalize().toString(),
                    Files.getLastModifiedTime(rootPom).toMillis());
        }

        Path modulePom = moduleDir.resolve("pom.xml");
        if (Files.exists(modulePom) && !modulePom.toAbsolutePath().normalize()
                .equals(rootPom.toAbsolutePath().normalize())) {
            timestamps.put(modulePom.toAbsolutePath().normalize().toString(),
                    Files.getLastModifiedTime(modulePom).toMillis());
        }

        Path gradleBuild = moduleDir.resolve("build.gradle");
        if (Files.exists(gradleBuild)) {
            timestamps.put(gradleBuild.toAbsolutePath().normalize().toString(),
                    Files.getLastModifiedTime(gradleBuild).toMillis());
        }
        Path gradleBuildKts = moduleDir.resolve("build.gradle.kts");
        if (Files.exists(gradleBuildKts)) {
            timestamps.put(gradleBuildKts.toAbsolutePath().normalize().toString(),
                    Files.getLastModifiedTime(gradleBuildKts).toMillis());
        }

        return timestamps;
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
        return application.getPathManager().getMcpLangToolsRoot()
                .resolve(CACHE_DIR_NAME)
                .resolve(dirName);
    }

    private static class CacheEntry {
        Map<String, Long> pomTimestamps;
        ClasspathInfo classpathInfo;
    }
}
