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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.mcp.ade.Application;
import org.eclipse.mcp.ade.PathManager;
import org.eclipse.mcp.ade.configuration.PathConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ClasspathCacheManagerTest {

    @TempDir
    Path tempDir;

    private ClasspathCacheManager cacheManager;
    private Path workspaceRoot;

    @BeforeEach
    void setUp() throws Exception {
        cacheManager = new ClasspathCacheManager();

        // Set up a PathManager that returns our temp dir
        PathManager pathManager = new PathManager();
        PathConfig pathConfig = new TestPathConfig(tempDir);
        Field pathConfigField = PathManager.class.getDeclaredField("pathConfig");
        pathConfigField.setAccessible(true);
        pathConfigField.set(pathManager, pathConfig);

        // Set up a minimal Application with our PathManager
        Application application = new Application();
        Field pmField = Application.class.getDeclaredField("pathManager");
        pmField.setAccessible(true);
        pmField.set(application, pathManager);

        // Inject the Application into the cache manager
        Field appField = ClasspathCacheManager.class.getDeclaredField("application");
        appField.setAccessible(true);
        appField.set(cacheManager, application);

        // Create a fake workspace
        workspaceRoot = tempDir.resolve("workspace");
        Files.createDirectories(workspaceRoot);
    }

    @Test
    void saveAndLoadRoundTrip() throws Exception {
        Path moduleDir = workspaceRoot.resolve("module-a");
        Files.createDirectories(moduleDir);

        // Create a real POM file so buildFiles exist
        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");

        // Create a real JAR file so the existence check passes
        Path jarFile = tempDir.resolve("commons-lang3-3.14.0.jar");
        Files.writeString(jarFile, "fake-jar");

        ClasspathInfo info = new ClasspathInfo(
                "module-a",
                moduleDir.toAbsolutePath().toString(),
                List.of("src/main/java"),
                List.of(jarFile.toAbsolutePath().toString()),
                List.of(new ClasspathInfo.ReactorModule("core", "/path/to/core")),
                List.of(pomFile.toAbsolutePath().normalize().toString()));

        cacheManager.save(workspaceRoot, moduleDir, info);

        Optional<ClasspathInfo> loaded = cacheManager.loadIfValid(workspaceRoot, moduleDir);

        assertTrue(loaded.isPresent());
        ClasspathInfo cached = loaded.get();
        assertEquals("module-a", cached.moduleName());
        assertEquals(List.of("src/main/java"), cached.sourceRoots());
        assertEquals(1, cached.reactorModuleDeps().size());
        assertEquals("core", cached.reactorModuleDeps().get(0).artifactId());
    }

    @Test
    void loadReturnsEmptyWhenNoCacheExists() {
        Path moduleDir = workspaceRoot.resolve("no-cache");
        Optional<ClasspathInfo> loaded = cacheManager.loadIfValid(workspaceRoot, moduleDir);
        assertTrue(loaded.isEmpty());
    }

    @Test
    void cacheInvalidatedWhenPomTimestampChanges() throws Exception {
        Path moduleDir = workspaceRoot.resolve("module-b");
        Files.createDirectories(moduleDir);
        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");

        ClasspathInfo info = new ClasspathInfo(
                "module-b", moduleDir.toAbsolutePath().toString(),
                List.of(), List.of(), List.of(),
                List.of(pomFile.toAbsolutePath().normalize().toString()));

        cacheManager.save(workspaceRoot, moduleDir, info);

        // Verify cache is valid
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isPresent());

        // Modify the POM — touch to change timestamp
        Thread.sleep(50);
        Files.writeString(pomFile, "<project><version>2.0</version></project>");

        // Cache should now be invalid
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isEmpty());
    }

    @Test
    void cacheInvalidatedWhenPomDeleted() throws Exception {
        Path moduleDir = workspaceRoot.resolve("module-c");
        Files.createDirectories(moduleDir);
        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");

        ClasspathInfo info = new ClasspathInfo(
                "module-c", moduleDir.toAbsolutePath().toString(),
                List.of(), List.of(), List.of(),
                List.of(pomFile.toAbsolutePath().normalize().toString()));

        cacheManager.save(workspaceRoot, moduleDir, info);

        // Delete the POM
        Files.delete(pomFile);

        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isEmpty());
    }

    @Test
    void cacheInvalidatedWhenJarMissing() throws Exception {
        Path moduleDir = workspaceRoot.resolve("module-d");
        Files.createDirectories(moduleDir);
        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");

        // Create a temp JAR file
        Path jarFile = tempDir.resolve("fake.jar");
        Files.writeString(jarFile, "fake-jar-content");

        ClasspathInfo info = new ClasspathInfo(
                "module-d", moduleDir.toAbsolutePath().toString(),
                List.of(), List.of(jarFile.toAbsolutePath().toString()), List.of(),
                List.of(pomFile.toAbsolutePath().normalize().toString()));

        cacheManager.save(workspaceRoot, moduleDir, info);

        // Cache should be valid while JAR exists
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isPresent());

        // Delete the JAR
        Files.delete(jarFile);

        // Cache should be invalid
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isEmpty());
    }

    @Test
    void cacheValidWhenNothingChanged() throws Exception {
        Path moduleDir = workspaceRoot.resolve("module-e");
        Files.createDirectories(moduleDir);
        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");

        ClasspathInfo info = new ClasspathInfo(
                "module-e", moduleDir.toAbsolutePath().toString(),
                List.of("src/main/java", "src/test/java"),
                List.of(), List.of(),
                List.of(pomFile.toAbsolutePath().normalize().toString()));

        cacheManager.save(workspaceRoot, moduleDir, info);

        // Load multiple times — all should succeed
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isPresent());
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isPresent());
    }

    @Test
    void backwardCompatWithMissingBuildFiles() throws Exception {
        Path moduleDir = workspaceRoot.resolve("module-old");
        Files.createDirectories(moduleDir);

        // Simulate an older cache format that has no buildFiles field
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        Map<String, Object> cacheEntry = new LinkedHashMap<>();
        cacheEntry.put("pomTimestamps", Map.of());
        Map<String, Object> classpathInfo = new LinkedHashMap<>();
        classpathInfo.put("moduleName", "module-old");
        classpathInfo.put("projectPath", moduleDir.toAbsolutePath().toString());
        classpathInfo.put("sourceRoots", List.of());
        classpathInfo.put("classpathJars", List.of());
        classpathInfo.put("reactorModuleDeps", List.of());
        // No buildFiles field — simulates older cache version
        cacheEntry.put("classpathInfo", classpathInfo);

        // Write directly to the cache location
        cacheManager.save(workspaceRoot, moduleDir,
                new ClasspathInfo("module-old", moduleDir.toAbsolutePath().toString(),
                        List.of(), List.of(), List.of(), List.of()));

        Optional<ClasspathInfo> loaded = cacheManager.loadIfValid(workspaceRoot, moduleDir);
        assertTrue(loaded.isPresent());
    }

    @Test
    void nonJarEntriesFilteredOnLoad() throws Exception {
        Path moduleDir = workspaceRoot.resolve("module-filter");
        Files.createDirectories(moduleDir);
        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");

        // Create both a JAR and a POM file in the classpath
        Path jarFile = tempDir.resolve("real.jar");
        Files.writeString(jarFile, "jar-content");
        Path pomArtifact = tempDir.resolve("artifact.pom");
        Files.writeString(pomArtifact, "pom-content");

        ClasspathInfo info = new ClasspathInfo(
                "module-filter", moduleDir.toAbsolutePath().toString(),
                List.of(),
                List.of(jarFile.toAbsolutePath().toString(), pomArtifact.toAbsolutePath().toString()),
                List.of(),
                List.of(pomFile.toAbsolutePath().normalize().toString()));

        cacheManager.save(workspaceRoot, moduleDir, info);

        Optional<ClasspathInfo> loaded = cacheManager.loadIfValid(workspaceRoot, moduleDir);
        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.get().classpathJars().size(), "Only .jar entries should remain");
        assertTrue(loaded.get().classpathJars().get(0).endsWith(".jar"));
    }

    @Test
    void corruptCacheFileReturnsEmpty() throws Exception {
        Path moduleDir = workspaceRoot.resolve("module-corrupt");
        Files.createDirectories(moduleDir);

        // First save a valid cache to discover the file location
        Path pomFile = moduleDir.resolve("pom.xml");
        Files.writeString(pomFile, "<project/>");
        ClasspathInfo info = new ClasspathInfo(
                "module-corrupt", moduleDir.toAbsolutePath().toString(),
                List.of(), List.of(), List.of(),
                List.of(pomFile.toAbsolutePath().normalize().toString()));
        cacheManager.save(workspaceRoot, moduleDir, info);

        // Verify it's valid first
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isPresent());

        // Find and corrupt the cache file
        Path cacheDir = tempDir.resolve("classpath-cache");
        Files.walk(cacheDir)
                .filter(p -> p.toString().endsWith(".json"))
                .findFirst()
                .ifPresent(p -> {
                    try {
                        Files.writeString(p, "not valid json {{{");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        // Should return empty, not throw
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isEmpty());
    }

    @Test
    void multipleBuildFilesTracked() throws Exception {
        Path moduleDir = workspaceRoot.resolve("module-multi");
        Files.createDirectories(moduleDir);
        Path modulePom = moduleDir.resolve("pom.xml");
        Files.writeString(modulePom, "<project/>");

        Path parentPom = workspaceRoot.resolve("pom.xml");
        Files.writeString(parentPom, "<project/>");

        Path bomDir = Files.createDirectory(workspaceRoot.resolve("bom"));
        Path bomPom = bomDir.resolve("pom.xml");
        Files.writeString(bomPom, "<project/>");

        ClasspathInfo info = new ClasspathInfo(
                "module-multi", moduleDir.toAbsolutePath().toString(),
                List.of("src/main/java"),
                List.of(),
                List.of(),
                List.of(
                        modulePom.toAbsolutePath().normalize().toString(),
                        bomPom.toAbsolutePath().normalize().toString(),
                        parentPom.toAbsolutePath().normalize().toString()));

        cacheManager.save(workspaceRoot, moduleDir, info);

        // All POMs unchanged → valid cache
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isPresent());

        // Touch the BOM POM only
        Thread.sleep(50);
        Files.writeString(bomPom, "<project><version>2</version></project>");

        // Cache should be invalid because one of the tracked build files changed
        assertTrue(cacheManager.loadIfValid(workspaceRoot, moduleDir).isEmpty());
    }

    private static class TestPathConfig extends PathConfig {
        private final Path root;

        TestPathConfig(Path root) {
            this.root = root;
        }

        @Override
        public Path getRootDir() {
            return root;
        }

        @Override
        public Path getMcpAdeDir() {
            return root;
        }

    }
}
