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

import com.ibm.mcp.languagetools.extensions.jdtls.classpath.MavenClasspathExtractor.MavenDependency;
import com.ibm.mcp.languagetools.extensions.jdtls.classpath.MavenClasspathExtractor.PomInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MavenClasspathExtractorTest {

    private final MavenClasspathExtractor extractor = new MavenClasspathExtractor();

    // ---- parsePomSax ----

    @Test
    void parsePomSaxSimplePom(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        PomInfo info = extractor.parsePomSax(pom);

        assertNotNull(info);
        assertEquals("com.example", info.groupId);
        assertEquals("my-app", info.artifactId);
        assertEquals("1.0.0", info.version);
        assertFalse(info.hasParent);
        assertFalse(info.isReactorPom());
    }

    @Test
    void parsePomSaxWithParent(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                        <relativePath>../parent</relativePath>
                    </parent>
                    <artifactId>child</artifactId>
                </project>
                """);

        PomInfo info = extractor.parsePomSax(pom);

        assertNotNull(info);
        assertTrue(info.hasParent);
        assertEquals("../parent", info.parentRelativePath);
        assertEquals("child", info.artifactId);
    }

    @Test
    void parsePomSaxReactorPom(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>module-a</module>
                        <module>module-b</module>
                    </modules>
                </project>
                """);

        PomInfo info = extractor.parsePomSax(pom);

        assertNotNull(info);
        assertTrue(info.isReactorPom());
        assertEquals("pom", info.packaging);
        assertEquals(List.of("module-a", "module-b"), info.moduleNames);
    }

    @Test
    void parsePomSaxWithDependencies(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.14.0</version>
                        </dependency>
                        <dependency>
                            <groupId>junit</groupId>
                            <artifactId>junit</artifactId>
                            <version>4.13</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);

        PomInfo info = extractor.parsePomSax(pom);

        assertNotNull(info);
        assertEquals(1, info.dependencies.size(), "test-scoped dependency should be excluded");
        assertEquals("commons-lang3", info.dependencies.get(0).artifactId());
        assertEquals("org.apache.commons", info.dependencies.get(0).groupId());
        assertEquals("3.14.0", info.dependencies.get(0).version());
    }

    @Test
    void parsePomSaxWithProperties(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <commons.version>3.14.0</commons.version>
                        <java.version>17</java.version>
                    </properties>
                </project>
                """);

        PomInfo info = extractor.parsePomSax(pom);

        assertNotNull(info);
        assertEquals("3.14.0", info.properties.get("commons.version"));
        assertEquals("17", info.properties.get("java.version"));
    }

    @Test
    void parsePomSaxWithDependencyManagement(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.google.guava</groupId>
                                <artifactId>guava</artifactId>
                                <version>32.0.0-jre</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.14.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        PomInfo info = extractor.parsePomSax(pom);

        assertNotNull(info);
        assertEquals(1, info.dependencies.size(),
                "dependencyManagement deps should not be included in direct dependencies");
        assertEquals("commons-lang3", info.dependencies.get(0).artifactId());
    }

    @Test
    void parsePomSaxReturnsNullForInvalidXml(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, "not xml at all");

        PomInfo info = extractor.parsePomSax(pom);

        assertNull(info);
    }

    @Test
    void parsePomSaxParentDefaultRelativePath(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                </project>
                """);

        PomInfo info = extractor.parsePomSax(pom);

        assertNotNull(info);
        assertTrue(info.hasParent);
        assertNull(info.parentRelativePath, "default relativePath should be null (caller assumes '..')");
    }

    // ---- resolveProperty ----

    @Test
    void resolvePropertySimple() {
        Map<String, String> props = Map.of("my.version", "1.0.0");
        assertEquals("1.0.0", extractor.resolveProperty("${my.version}", props));
    }

    @Test
    void resolvePropertyNested() {
        Map<String, String> props = Map.of("base", "1.0", "full", "${base}.0");
        assertEquals("1.0.0", extractor.resolveProperty("${full}", props));
    }

    @Test
    void resolvePropertyNoPlaceholder() {
        assertEquals("literal", extractor.resolveProperty("literal", Map.of()));
    }

    @Test
    void resolvePropertyUnresolved() {
        assertEquals("${unknown}", extractor.resolveProperty("${unknown}", Map.of()));
    }

    @Test
    void resolvePropertyNull() {
        assertNull(extractor.resolveProperty(null, Map.of()));
    }

    @Test
    void resolvePropertyInfiniteLoopProtection() {
        Map<String, String> props = Map.of("a", "${b}", "b", "${a}");
        String result = extractor.resolveProperty("${a}", props);
        assertNotNull(result);
    }

    // ---- collectPropertiesFromHierarchy ----

    @Test
    void collectPropertiesFromParentChain(@TempDir Path workspace) throws IOException {
        // workspace/pom.xml (root)
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <properties>
                        <root.prop>from-root</root.prop>
                    </properties>
                    <modules>
                        <module>parent</module>
                    </modules>
                </project>
                """);

        // workspace/parent/pom.xml (intermediate parent)
        Path parentDir = Files.createDirectory(workspace.resolve("parent"));
        Files.writeString(parentDir.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>root</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>parent</artifactId>
                    <packaging>pom</packaging>
                    <properties>
                        <parent.prop>from-parent</parent.prop>
                    </properties>
                    <modules>
                        <module>child</module>
                    </modules>
                </project>
                """);

        // workspace/parent/child/pom.xml
        Path childDir = Files.createDirectory(parentDir.resolve("child"));
        Files.writeString(childDir.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <properties>
                        <child.prop>from-child</child.prop>
                    </properties>
                </project>
                """);

        Map<String, String> properties = new HashMap<>();
        Set<String> buildFiles = new LinkedHashSet<>();
        extractor.collectPropertiesFromHierarchy(
                childDir.resolve("pom.xml"), workspace, properties, buildFiles);

        assertEquals("from-root", properties.get("root.prop"));
        assertEquals("from-parent", properties.get("parent.prop"));
        assertEquals("from-child", properties.get("child.prop"));
    }

    @Test
    void collectPropertiesChildOverridesParent(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <shared.prop>root-value</shared.prop>
                    </properties>
                </project>
                """);

        Path childDir = Files.createDirectory(workspace.resolve("child"));
        Files.writeString(childDir.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>root</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <properties>
                        <shared.prop>child-value</shared.prop>
                    </properties>
                </project>
                """);

        Map<String, String> properties = new HashMap<>();
        Set<String> buildFiles = new LinkedHashSet<>();
        extractor.collectPropertiesFromHierarchy(
                childDir.resolve("pom.xml"), workspace, properties, buildFiles);

        assertEquals("child-value", properties.get("shared.prop"));
    }

    // ---- buildFiles tracking ----

    @Test
    void buildFilesTrackEntireParentChain(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                </project>
                """);

        // bom/pom.xml is a sibling, not an ancestor directory
        Path bomDir = Files.createDirectory(workspace.resolve("bom"));
        Files.writeString(bomDir.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>root</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>bom</artifactId>
                    <packaging>pom</packaging>
                    <properties>
                        <lib.version>2.0</lib.version>
                    </properties>
                </project>
                """);

        Path moduleDir = Files.createDirectory(workspace.resolve("module-a"));
        Files.writeString(moduleDir.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>bom</artifactId>
                        <version>1.0.0</version>
                        <relativePath>../bom</relativePath>
                    </parent>
                    <artifactId>module-a</artifactId>
                </project>
                """);

        Set<String> buildFiles = new LinkedHashSet<>();
        Map<String, String> properties = new HashMap<>();
        extractor.collectPropertiesFromHierarchy(
                moduleDir.resolve("pom.xml"), workspace, properties, buildFiles);

        assertEquals(3, buildFiles.size(), "Should track module, bom, and root POMs");
        assertTrue(buildFiles.stream().anyMatch(f -> f.contains("module-a")));
        assertTrue(buildFiles.stream().anyMatch(f -> f.contains("bom")));
        assertTrue(buildFiles.stream().anyMatch(f -> f.endsWith("pom.xml") && !f.contains("module-a") && !f.contains("bom")));
        assertEquals("2.0", properties.get("lib.version"));
    }

    @Test
    void buildFilesNoDuplicates(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                </project>
                """);

        Path childDir = Files.createDirectory(workspace.resolve("child"));
        Files.writeString(childDir.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>root</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                </project>
                """);

        Set<String> buildFiles = new LinkedHashSet<>();
        Map<String, String> props = new HashMap<>();
        // Call twice to verify dedup
        extractor.collectPropertiesFromHierarchy(childDir.resolve("pom.xml"), workspace, props, buildFiles);
        extractor.collectPropertiesFromHierarchy(childDir.resolve("pom.xml"), workspace, props, buildFiles);

        assertEquals(2, buildFiles.size(), "Set should dedup");
    }

    // ---- parseDependencies ----

    @Test
    void parseDependenciesResolvesPropertyVersions(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <commons.version>3.14.0</commons.version>
                    </properties>
                </project>
                """);

        Path childDir = Files.createDirectory(workspace.resolve("child"));
        Files.writeString(childDir.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>root</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>${commons.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Set<String> buildFiles = new LinkedHashSet<>();
        List<MavenDependency> deps = extractor.parseDependencies(
                childDir.resolve("pom.xml"), workspace, buildFiles);

        assertEquals(1, deps.size());
        assertEquals("3.14.0", deps.get(0).version());
    }

    @Test
    void parseDependenciesUsesProjectVersion(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>2.5.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>sibling</artifactId>
                            <version>${project.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Set<String> buildFiles = new LinkedHashSet<>();
        List<MavenDependency> deps = extractor.parseDependencies(
                workspace.resolve("pom.xml"), workspace, buildFiles);

        assertEquals(1, deps.size());
        assertEquals("2.5.0", deps.get(0).version());
    }

    // ---- scanReactorModules ----

    @Test
    void scanReactorModulesFindsAllModules(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>module-a</module>
                        <module>module-b</module>
                    </modules>
                </project>
                """);

        Path moduleA = Files.createDirectory(workspace.resolve("module-a"));
        Files.writeString(moduleA.resolve("pom.xml"), """
                <project>
                    <artifactId>module-a</artifactId>
                </project>
                """);

        Path moduleB = Files.createDirectory(workspace.resolve("module-b"));
        Files.writeString(moduleB.resolve("pom.xml"), """
                <project>
                    <artifactId>module-b</artifactId>
                </project>
                """);

        Map<String, Path> modules = extractor.scanReactorModules(workspace);

        assertEquals(3, modules.size());
        assertTrue(modules.containsKey("root"));
        assertTrue(modules.containsKey("module-a"));
        assertTrue(modules.containsKey("module-b"));
    }

    @Test
    void scanReactorModulesRecursive(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <artifactId>root</artifactId>
                    <packaging>pom</packaging>
                    <modules><module>sub</module></modules>
                </project>
                """);

        Path sub = Files.createDirectory(workspace.resolve("sub"));
        Files.writeString(sub.resolve("pom.xml"), """
                <project>
                    <artifactId>sub</artifactId>
                    <packaging>pom</packaging>
                    <modules><module>deep</module></modules>
                </project>
                """);

        Path deep = Files.createDirectory(sub.resolve("deep"));
        Files.writeString(deep.resolve("pom.xml"), """
                <project>
                    <artifactId>deep</artifactId>
                </project>
                """);

        Map<String, Path> modules = extractor.scanReactorModules(workspace);

        assertEquals(3, modules.size());
        assertTrue(modules.containsKey("deep"));
    }

    // ---- detectSourceRoots ----

    @Test
    void detectSourceRootsStandardLayout(@TempDir Path moduleDir) throws IOException {
        Files.createDirectories(moduleDir.resolve("src/main/java"));
        Files.createDirectories(moduleDir.resolve("src/main/resources"));
        Files.createDirectories(moduleDir.resolve("src/test/java"));

        List<String> roots = extractor.detectSourceRoots(moduleDir);

        assertTrue(roots.contains("src/main/java"));
        assertTrue(roots.contains("src/main/resources"));
        assertTrue(roots.contains("src/test/java"));
    }

    @Test
    void detectSourceRootsFallbackToSrc(@TempDir Path moduleDir) throws IOException {
        Files.createDirectories(moduleDir.resolve("src"));

        List<String> roots = extractor.detectSourceRoots(moduleDir);

        assertEquals(List.of("src"), roots);
    }

    @Test
    void detectSourceRootsEmptyModule(@TempDir Path moduleDir) {
        List<String> roots = extractor.detectSourceRoots(moduleDir);

        assertTrue(roots.isEmpty());
    }

    // ---- resolveJarInLocalRepo ----

    @Test
    void resolveJarInLocalRepoBuildsCorrectPath(@TempDir Path m2Repo) {
        MavenDependency dep = new MavenDependency("org.apache.commons", "commons-lang3", "3.14.0");

        Path jarPath = extractor.resolveJarInLocalRepo(m2Repo, dep);

        Path expected = m2Repo.resolve("org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar");
        assertEquals(expected, jarPath);
    }

    @Test
    void resolveJarInLocalRepoNullVersion(@TempDir Path m2Repo) {
        MavenDependency dep = new MavenDependency("com.example", "lib", null);

        Path jarPath = extractor.resolveJarInLocalRepo(m2Repo, dep);

        assertNull(jarPath);
    }

    // ---- findMavenExecutable ----

    @Test
    void findMavenExecutableFindsWrapper(@TempDir Path project) throws IOException {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapperName = isWindows ? "mvnw.cmd" : "mvnw";
        Files.writeString(project.resolve(wrapperName), "#!/bin/sh\necho wrapper");

        Path mvn = extractor.findMavenExecutable(project);

        assertEquals(project.resolve(wrapperName), mvn);
    }

    @Test
    void findMavenExecutableFallsBackToSystem(@TempDir Path project) {
        Path mvn = extractor.findMavenExecutable(project);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertEquals(Path.of(isWindows ? "mvn.cmd" : "mvn"), mvn);
    }

    // ---- cycle detection in collectPropertiesFromHierarchy ----

    @Test
    void collectPropertiesCycleDetection(@TempDir Path workspace) throws IOException {
        // POM points to itself via relativePath
        Path pomFile = workspace.resolve("pom.xml");
        Files.writeString(pomFile, """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>self</artifactId>
                        <version>1.0.0</version>
                        <relativePath>.</relativePath>
                    </parent>
                    <artifactId>self</artifactId>
                    <properties>
                        <my.prop>value</my.prop>
                    </properties>
                </project>
                """);

        Map<String, String> properties = new HashMap<>();
        Set<String> buildFiles = new LinkedHashSet<>();
        // Should not stack overflow
        extractor.collectPropertiesFromHierarchy(pomFile, workspace, properties, buildFiles);

        assertEquals("value", properties.get("my.prop"));
        assertEquals(1, buildFiles.size());
    }

    @Test
    void collectPropertiesMutualCycleDetection(@TempDir Path workspace) throws IOException {
        // A points to B, B points to A
        Path dirA = Files.createDirectory(workspace.resolve("a"));
        Path dirB = Files.createDirectory(workspace.resolve("b"));
        Files.writeString(dirA.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>b</artifactId>
                        <version>1.0.0</version>
                        <relativePath>../b</relativePath>
                    </parent>
                    <artifactId>a</artifactId>
                </project>
                """);
        Files.writeString(dirB.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>a</artifactId>
                        <version>1.0.0</version>
                        <relativePath>../a</relativePath>
                    </parent>
                    <artifactId>b</artifactId>
                </project>
                """);

        Map<String, String> properties = new HashMap<>();
        Set<String> buildFiles = new LinkedHashSet<>();
        // Should not stack overflow
        extractor.collectPropertiesFromHierarchy(
                dirA.resolve("pom.xml"), workspace, properties, buildFiles);

        assertEquals(2, buildFiles.size());
    }

    // ---- dependencyManagement version resolution ----

    @Test
    void parsePomSaxExtractsManagedVersions(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.google.guava</groupId>
                                <artifactId>guava</artifactId>
                                <version>32.0.0-jre</version>
                            </dependency>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.14.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        PomInfo info = extractor.parsePomSax(pom);

        assertNotNull(info);
        assertEquals("32.0.0-jre", info.managedVersions.get("com.google.guava:guava"));
        assertEquals("3.14.0", info.managedVersions.get("org.apache.commons:commons-lang3"));
    }

    @Test
    void parseDependenciesFallsBackToManagedVersion(@TempDir Path workspace) throws IOException {
        // Parent declares dependencyManagement
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.google.guava</groupId>
                                <artifactId>guava</artifactId>
                                <version>32.0.0-jre</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        // Child declares dep WITHOUT version
        Path childDir = Files.createDirectory(workspace.resolve("child"));
        Files.writeString(childDir.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Set<String> buildFiles = new LinkedHashSet<>();
        List<MavenDependency> deps = extractor.parseDependencies(
                childDir.resolve("pom.xml"), workspace, buildFiles);

        assertEquals(1, deps.size());
        assertEquals("32.0.0-jre", deps.get(0).version(),
                "Version should come from parent's dependencyManagement");
    }

    @Test
    void parseDependenciesManagedVersionWithProperty(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <properties>
                        <guava.version>32.0.0-jre</guava.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.google.guava</groupId>
                                <artifactId>guava</artifactId>
                                <version>${guava.version}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        Path childDir = Files.createDirectory(workspace.resolve("child"));
        Files.writeString(childDir.resolve("pom.xml"), """
                <project>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>com.google.guava</groupId>
                            <artifactId>guava</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        Set<String> buildFiles = new LinkedHashSet<>();
        List<MavenDependency> deps = extractor.parseDependencies(
                childDir.resolve("pom.xml"), workspace, buildFiles);

        assertEquals(1, deps.size());
        assertEquals("32.0.0-jre", deps.get(0).version(),
                "Managed version property should be resolved");
    }

    @Test
    void collectPropertiesAlsoCollectsManagedVersions(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>root</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.slf4j</groupId>
                                <artifactId>slf4j-api</artifactId>
                                <version>2.0.9</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);

        Map<String, String> properties = new HashMap<>();
        Map<String, String> managedVersions = new HashMap<>();
        Set<String> buildFiles = new LinkedHashSet<>();
        extractor.collectPropertiesFromHierarchy(
                workspace.resolve("pom.xml"), workspace, properties, managedVersions, buildFiles);

        assertEquals("2.0.9", managedVersions.get("org.slf4j:slf4j-api"));
    }

    // ---- provided scope filtering ----

    @Test
    void parsePomSaxExcludesProvidedScope(@TempDir Path dir) throws IOException {
        Path pom = dir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.14.0</version>
                        </dependency>
                        <dependency>
                            <groupId>javax.servlet</groupId>
                            <artifactId>javax.servlet-api</artifactId>
                            <version>4.0.1</version>
                            <scope>provided</scope>
                        </dependency>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>runtime-lib</artifactId>
                            <version>1.0</version>
                            <scope>runtime</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);

        PomInfo info = extractor.parsePomSax(pom);

        assertNotNull(info);
        assertEquals(2, info.dependencies.size(), "provided scope should be excluded");
        assertTrue(info.dependencies.stream().anyMatch(d -> "commons-lang3".equals(d.artifactId())));
        assertTrue(info.dependencies.stream().anyMatch(d -> "runtime-lib".equals(d.artifactId())));
        assertFalse(info.dependencies.stream().anyMatch(d -> "javax.servlet-api".equals(d.artifactId())));
    }

    // ---- canHandle ----

    @Test
    void canHandleWithPom(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");

        assertTrue(extractor.canHandle(workspace));
    }

    @Test
    void canHandleWithoutPom(@TempDir Path workspace) {
        assertFalse(extractor.canHandle(workspace));
    }
}
