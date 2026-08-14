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

import com.ibm.mcp.languagetools.progress.NoOpProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link MavenClasspathExtractor} that execute real Maven commands
 * using the project's Maven wrapper ({@code mvnw}/{@code mvnw.cmd}).
 *
 * <p>Each test creates a temporary Maven project, copies the project's Maven wrapper
 * into it, and executes real classpath extraction. This validates the full pipeline
 * including {@code mvn dependency:build-classpath} execution and POM-based fallback.</p>
 *
 * <p>These tests are automatically skipped if the project's Maven wrapper is not found
 * or not executable.</p>
 */
@EnabledIf("isMavenWrapperAvailable")
class MavenClasspathExtractorIntegrationTest {

    private final MavenClasspathExtractor extractor = new MavenClasspathExtractor();

    /**
     * The project root where mvnw/mvnw.cmd lives.
     * Resolved by walking up from the test class location.
     */
    private static Path projectRoot;

    private static final ProgressMonitor NO_OP_PROGRESS = NoOpProgressMonitor.INSTANCE;

    @BeforeAll
    static void findProjectRoot() {
        // Walk up from the current working directory to find mvnw
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("mvnw")) || Files.exists(dir.resolve("mvnw.cmd"))) {
                projectRoot = dir;
                return;
            }
            dir = dir.getParent();
        }
    }

    static boolean isMavenWrapperAvailable() {
        findProjectRoot();
        if (projectRoot == null) {
            return false;
        }
        try {
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            String wrapper = isWindows ? "mvnw.cmd" : "mvnw";
            Path mvnw = projectRoot.resolve(wrapper);
            if (!Files.exists(mvnw)) {
                return false;
            }
            ProcessBuilder pb = new ProcessBuilder(mvnw.toAbsolutePath().toString(), "--version");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exit = process.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Copies the project's Maven wrapper ({@code mvnw}, {@code mvnw.cmd}, {@code .mvn/})
     * into the target workspace so that {@link MavenClasspathExtractor#findMavenExecutable}
     * picks it up automatically.
     */
    private void installMavenWrapper(Path workspace) throws IOException {
        // Copy mvnw / mvnw.cmd
        for (String name : new String[]{"mvnw", "mvnw.cmd"}) {
            Path src = projectRoot.resolve(name);
            if (Files.exists(src)) {
                Files.copy(src, workspace.resolve(name), StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        // Copy .mvn directory (contains wrapper descriptor and wrapper JAR)
        Path mvnDir = projectRoot.resolve(".mvn");
        if (Files.isDirectory(mvnDir)) {
            Files.walk(mvnDir).forEach(source -> {
                try {
                    Path target = workspace.resolve(projectRoot.relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // ---- extract() with real Maven execution ----

    @Test
    void extractSimpleProjectWithMvnBuildClasspath(@TempDir Path workspace) throws Exception {
        installMavenWrapper(workspace);

        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.14.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.createDirectories(workspace.resolve("src/main/java"));

        ClasspathInfo info = extractor.extract(workspace, workspace, NO_OP_PROGRESS);

        assertNotNull(info);
        assertEquals("test-project", info.moduleName());
        assertTrue(info.classpathJars().stream()
                        .anyMatch(jar -> jar.contains("commons-lang3") && jar.endsWith(".jar")),
                "Should contain commons-lang3 JAR: " + info.classpathJars());
        assertTrue(info.sourceRoots().contains("src/main/java"));
        assertFalse(info.buildFiles().isEmpty(), "Build files should be tracked");
    }

    @Test
    void extractMultiModuleWithReactorDeps(@TempDir Path workspace) throws Exception {
        installMavenWrapper(workspace);

        // Root POM (reactor)
        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>test-reactor</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>core</module>
                        <module>app</module>
                    </modules>
                </project>
                """);

        // Core module
        Path coreDir = Files.createDirectory(workspace.resolve("core"));
        Files.writeString(coreDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>test-reactor</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>core</artifactId>
                </project>
                """);
        Files.createDirectories(coreDir.resolve("src/main/java"));

        // App module depends on core (reactor module)
        Path appDir = Files.createDirectory(workspace.resolve("app"));
        Files.writeString(appDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>test-reactor</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>app</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.test</groupId>
                            <artifactId>core</artifactId>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.createDirectories(appDir.resolve("src/main/java"));

        // Extract for the app module — core is a reactor dep, should skip Maven
        ClasspathInfo info = extractor.extract(workspace, appDir, NO_OP_PROGRESS);

        assertNotNull(info);
        assertEquals("app", info.moduleName());
        assertTrue(info.reactorModuleDeps().stream()
                        .anyMatch(rm -> "core".equals(rm.artifactId())),
                "core should be a reactor module dep");
    }

    @Test
    void extractReactorPomSkipsClasspath(@TempDir Path workspace) throws Exception {
        installMavenWrapper(workspace);

        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>sub</module>
                    </modules>
                </project>
                """);
        Path subDir = Files.createDirectory(workspace.resolve("sub"));
        Files.writeString(subDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>sub</artifactId>
                </project>
                """);

        ClasspathInfo info = extractor.extract(workspace, workspace, NO_OP_PROGRESS);

        assertNotNull(info);
        assertTrue(info.classpathJars().isEmpty(), "Reactor POM should have no JARs");
    }

    @Test
    void extractWithPropertyVersionFromParent(@TempDir Path workspace) throws Exception {
        installMavenWrapper(workspace);

        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>parent-with-props</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <properties>
                        <commons.lang.version>3.14.0</commons.lang.version>
                    </properties>
                    <modules>
                        <module>child</module>
                    </modules>
                </project>
                """);

        Path childDir = Files.createDirectory(workspace.resolve("child"));
        Files.writeString(childDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>parent-with-props</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child-with-dep</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>${commons.lang.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.createDirectories(childDir.resolve("src/main/java"));

        ClasspathInfo info = extractor.extract(workspace, childDir, NO_OP_PROGRESS);

        assertNotNull(info);
        assertEquals("child-with-dep", info.moduleName());
        assertTrue(info.classpathJars().stream()
                        .anyMatch(jar -> jar.contains("commons-lang3-3.14.0.jar")),
                "Should resolve version from parent property: " + info.classpathJars());

        // Both child and parent POM should be tracked in buildFiles
        assertTrue(info.buildFiles().size() >= 2,
                "Should track at least child + parent POMs: " + info.buildFiles());
    }

    @Test
    void extractWithSiblingParentPom(@TempDir Path workspace) throws Exception {
        installMavenWrapper(workspace);

        // Root POM
        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>bom</module>
                        <module>app</module>
                    </modules>
                </project>
                """);

        // BOM (sibling parent with properties)
        Path bomDir = Files.createDirectory(workspace.resolve("bom"));
        Files.writeString(bomDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>root</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>bom</artifactId>
                    <packaging>pom</packaging>
                    <properties>
                        <guava.version>32.0.0-jre</guava.version>
                    </properties>
                </project>
                """);

        // App depends on BOM as parent (sibling relativePath)
        Path appDir = Files.createDirectory(workspace.resolve("app"));
        Files.writeString(appDir.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.test</groupId>
                        <artifactId>bom</artifactId>
                        <version>1.0.0</version>
                        <relativePath>../bom</relativePath>
                    </parent>
                    <artifactId>app</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                            <version>${guava.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.createDirectories(appDir.resolve("src/main/java"));

        ClasspathInfo info = extractor.extract(workspace, appDir, NO_OP_PROGRESS);

        assertNotNull(info);
        // buildFiles should include app/pom.xml, bom/pom.xml, and root/pom.xml
        assertTrue(info.buildFiles().size() >= 3,
                "Should track app, bom, and root POMs: " + info.buildFiles());
        assertTrue(info.buildFiles().stream().anyMatch(f -> f.contains("bom")),
                "Should track the sibling bom/pom.xml: " + info.buildFiles());
    }

    @Test
    void extractWithMultipleDependencies(@TempDir Path workspace) throws Exception {
        installMavenWrapper(workspace);

        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>multi-dep</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.14.0</version>
                        </dependency>
                        <dependency>
                            <groupId>com.google.code.gson</groupId>
                            <artifactId>gson</artifactId>
                            <version>2.10.1</version>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.createDirectories(workspace.resolve("src/test/java"));

        ClasspathInfo info = extractor.extract(workspace, workspace, NO_OP_PROGRESS);

        assertNotNull(info);
        // Should include compile deps and test-scoped deps (test scope is included)
        assertTrue(info.classpathJars().stream()
                        .anyMatch(jar -> jar.contains("commons-lang3")),
                "Should contain commons-lang3");
        assertTrue(info.classpathJars().stream()
                        .anyMatch(jar -> jar.contains("gson")),
                "Should contain gson");
        // Source roots
        assertTrue(info.sourceRoots().contains("src/main/java"));
        assertTrue(info.sourceRoots().contains("src/test/java"));
    }

    @Test
    void extractModuleWithNoDeps(@TempDir Path workspace) throws Exception {
        installMavenWrapper(workspace);

        Files.writeString(workspace.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.test</groupId>
                    <artifactId>no-deps</artifactId>
                    <version>1.0.0</version>
                </project>
                """);
        Files.createDirectories(workspace.resolve("src/main/java"));

        ClasspathInfo info = extractor.extract(workspace, workspace, NO_OP_PROGRESS);

        assertNotNull(info);
        assertEquals("no-deps", info.moduleName());
        assertTrue(info.classpathJars().isEmpty(), "No dependencies → no JARs");
        assertTrue(info.sourceRoots().contains("src/main/java"));
    }

    // ---- findMavenExecutable ----

    @Test
    void findMavenExecutablePrefersWrapper(@TempDir Path workspace) throws IOException {
        installMavenWrapper(workspace);

        Path found = extractor.findMavenExecutable(workspace);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String expectedName = isWindows ? "mvnw.cmd" : "mvnw";
        assertEquals(workspace.resolve(expectedName), found);
    }

    @Test
    void findMavenExecutableFallsBackToSystem(@TempDir Path workspace) {
        // No wrapper installed → falls back to system
        Path found = extractor.findMavenExecutable(workspace);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertEquals(Path.of(isWindows ? "mvn.cmd" : "mvn"), found);
    }

    // ---- canHandle ----

    @Test
    void canHandleDetectsMavenProject(@TempDir Path workspace) throws IOException {
        assertFalse(extractor.canHandle(workspace));

        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        assertTrue(extractor.canHandle(workspace));
    }
}
