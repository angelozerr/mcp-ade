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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.SAXParserFactory;

import org.jboss.logging.Logger;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import com.ibm.mcp.languagetools.extensions.jdtls.classpath.ClasspathInfo.ReactorModule;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Extracts classpath from Maven projects.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Try {@code mvn dependency:build-classpath} (fast, works if all deps are installed)</li>
 *   <li>If it fails (e.g., reactor SNAPSHOT modules not installed), fall back to
 *       POM parsing: identify reactor modules and build the classpath manually
 *       from {@code ~/.m2/repository}</li>
 * </ol>
 *
 * <p>Prioritizes the Maven wrapper ({@code mvnw}/{@code mvnw.cmd}) if present
 * in the project root, falling back to system {@code mvn} on PATH.</p>
 */
@ApplicationScoped
public class MavenClasspathExtractor extends AbstractClasspathExtractor {

    private static final Logger LOG = Logger.getLogger(MavenClasspathExtractor.class);

    @Override
    protected String unixWrapperName() { return "mvnw"; }

    @Override
    protected String windowsWrapperName() { return "mvnw.cmd"; }

    @Override
    protected String unixSystemName() { return "mvn"; }

    @Override
    protected String windowsSystemName() { return "mvn.cmd"; }

    @Override
    protected String buildToolOptsVar() { return "MAVEN_OPTS"; }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code true} if a {@code pom.xml} file exists at the workspace root.</p>
     */
    @Override
    public boolean canHandle(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("pom.xml"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Extraction proceeds through two strategies, tried in order:
     * <ol>
     *   <li><strong>Strategy 1</strong> — {@code mvn dependency:build-classpath}:
     *       fast and reliable when all dependencies are installed in the local repository.</li>
     *   <li><strong>Strategy 2</strong> — POM-based resolution: parses dependency declarations
     *       from the POM hierarchy, resolves JARs from {@code ~/.m2/repository}, and identifies
     *       reactor module dependencies as source project references.</li>
     * </ol>
     *
     * <p>Before either strategy, a fast-path check determines if <em>all</em> dependencies are
     * reactor modules — in that case, Maven is skipped entirely for maximum speed.</p>
     *
     * @param workspaceRoot the root of the multi-module project (must contain a {@code pom.xml})
     * @param moduleDir     the directory of the specific module to extract classpath for
     * @param progress      progress monitor for reporting download/resolution progress
     * @return the extracted classpath information including source roots, JARs, reactor deps,
     *         and the list of build files consulted (for cache invalidation)
     * @throws ClasspathExtractionException if no {@code pom.xml} exists in {@code moduleDir}
     *                                      or both strategies fail
     */
    @Override
    public ClasspathInfo extract(Path workspaceRoot, Path moduleDir, ProgressMonitor progress)
            throws ClasspathExtractionException {
        Path pomFile = moduleDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            throw new ClasspathExtractionException(
                    "No pom.xml found in module directory: " + moduleDir);
        }

        PomInfo pomInfo = parsePomSax(pomFile);
        String moduleName = pomInfo != null && pomInfo.artifactId != null
                ? pomInfo.artifactId : moduleDir.getFileName().toString();

        if (pomInfo != null && pomInfo.isReactorPom()) {
            LOG.infof("Skipping classpath extraction for reactor POM: %s", moduleDir);
            progress.reportProgress("Reactor POM detected, skipping classpath extraction");
            List<String> sourceRoots = detectSourceRoots(moduleDir);
            return new ClasspathInfo(moduleName, moduleDir.toAbsolutePath().toString(),
                    sourceRoots, List.of(), List.of(),
                    List.of(pomFile.toAbsolutePath().normalize().toString()));
        }

        // Fast path: if ALL dependencies are reactor modules, skip Maven entirely
        long start = System.currentTimeMillis();
        Map<String, Path> reactorModules = scanReactorModules(workspaceRoot);
        Set<String> buildFiles = new LinkedHashSet<>();
        List<MavenDependency> dependencies = parseDependencies(pomFile, workspaceRoot, buildFiles);

        List<ReactorModule> reactorDeps = new ArrayList<>();
        boolean allReactor = true;
        for (MavenDependency dep : dependencies) {
            Path reactorPath = reactorModules.get(dep.artifactId);
            if (reactorPath != null && Files.exists(reactorPath.resolve("pom.xml"))) {
                reactorDeps.add(new ReactorModule(dep.artifactId,
                        reactorPath.toAbsolutePath().toString()));
            } else {
                allReactor = false;
                break;
            }
        }

        if (allReactor && !dependencies.isEmpty()) {
            List<String> sourceRoots = detectSourceRoots(moduleDir);
            long elapsed = System.currentTimeMillis() - start;
            LOG.infof("All %d dependencies are reactor modules, skipping Maven (%d ms)",
                    dependencies.size(), elapsed);
            progress.reportProgress(String.format(
                    "All dependencies are reactor modules, skipping Maven (%d ms)", elapsed));
            return new ClasspathInfo(moduleName, moduleDir.toAbsolutePath().toString(),
                    sourceRoots, List.of(), reactorDeps, List.copyOf(buildFiles));
        }

        Path mvnExecutable = findMavenExecutable(workspaceRoot);
        LOG.infof("Using Maven executable: %s", mvnExecutable);

        // Strategy 1: try mvn dependency:build-classpath (fast path)
        try {
            progress.reportProgress("Resolving classpath for " + moduleName + " via Maven (dependency:build-classpath)...");
            start = System.currentTimeMillis();
            ClasspathInfo result = tryBuildClasspath(mvnExecutable, workspaceRoot, moduleDir,
                    moduleName, buildFiles, progress);
            if (result != null) {
                long elapsed = System.currentTimeMillis() - start;
                LOG.infof("Strategy 1 (dependency:build-classpath) completed in %d ms", elapsed);
                progress.reportProgress(String.format("Classpath resolved via Maven in %d ms (%d JARs)",
                        elapsed, result.classpathJars().size()));
                return result;
            }
        } catch (ClasspathExtractionException e) {
            LOG.infof("dependency:build-classpath failed, falling back to POM parsing: %s", e.getMessage());
        }

        // Strategy 2: POM-based resolution (handles missing reactor SNAPSHOTs)
        progress.reportProgress("Resolving classpath for " + moduleName + " from POM (reactor mode)...");
        start = System.currentTimeMillis();
        ClasspathInfo result = extractFromPom(workspaceRoot, moduleDir, moduleName,
                reactorModules, dependencies, buildFiles, progress);
        long elapsed = System.currentTimeMillis() - start;
        LOG.infof("Strategy 2 (POM parsing) completed in %d ms", elapsed);
        progress.reportProgress(String.format("Classpath resolved via POM parsing in %d ms (%d JARs, %d reactor modules)",
                elapsed, result.classpathJars().size(), result.reactorModuleDeps().size()));
        return result;
    }

    // ---- Strategy 1: mvn dependency:build-classpath ----

    private ClasspathInfo tryBuildClasspath(Path mvnExecutable, Path workspaceRoot,
                                             Path moduleDir, String moduleName,
                                             Set<String> buildFiles,
                                             ProgressMonitor progress)
            throws ClasspathExtractionException {
        try {
            Path tempFile = Files.createTempFile("mcp-classpath-", ".txt");
            try {
                List<String> classpathJars = runBuildClasspath(
                        mvnExecutable, workspaceRoot, moduleDir, moduleName, tempFile, progress);
                List<String> sourceRoots = detectSourceRoots(moduleDir);
                return new ClasspathInfo(moduleName, moduleDir.toAbsolutePath().toString(),
                        sourceRoots, classpathJars, List.of(), List.copyOf(buildFiles));
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw new ClasspathExtractionException("IO error during build-classpath", e);
        }
    }

    private List<String> runBuildClasspath(Path mvnExecutable, Path workspaceRoot,
                                           Path moduleDir, String moduleName,
                                           Path outputFile,
                                           ProgressMonitor progress)
            throws ClasspathExtractionException, IOException {
        boolean isSubModule = !workspaceRoot.equals(moduleDir);

        List<String> args = new ArrayList<>(List.of(
                "dependency:build-classpath",
                "-DincludeScope=test",
                "-Dmdep.outputFile=" + outputFile.toAbsolutePath()));
        if (isSubModule) {
            args.add("-pl");
            args.add(":" + moduleName);
        }
        args.add("-B");

        ProcessBuilder pb = createProcess(mvnExecutable, workspaceRoot,
                args.toArray(String[]::new));
        LOG.infof("Running: %s", String.join(" ", pb.command()));

        Process process = pb.start();
        StringBuilder errorOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                errorOutput.append(line).append('\n');
                LOG.debugf("Maven: %s", line);
                if (!line.isBlank()) {
                    progress.reportTrace(line);
                }
            }
        }

        int exitCode = waitForProcess(process);

        if (exitCode != 0) {
            throw new ClasspathExtractionException(
                    "Maven dependency:build-classpath failed (exit " + exitCode + "): "
                            + errorOutput.toString().trim());
        }

        if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
            return List.of();
        }

        String classpath = Files.readString(outputFile).trim();
        if (classpath.isEmpty()) {
            return List.of();
        }

        // Filter out non-JAR entries (e.g. .pom files from BOM/depchain dependencies)
        return Arrays.stream(classpath.split(File.pathSeparator))
                .filter(entry -> entry.endsWith(".jar"))
                .toList();
    }

    // ---- Strategy 2: POM-based resolution ----

    private ClasspathInfo extractFromPom(Path workspaceRoot, Path moduleDir,
                                          String moduleName,
                                          Map<String, Path> reactorModules,
                                          List<MavenDependency> dependencies,
                                          Set<String> buildFiles,
                                          ProgressMonitor progress)
            throws ClasspathExtractionException {
        try {
            // 0. Download dependencies first (best-effort)
            Path mvnExecutable = findMavenExecutable(workspaceRoot);
            downloadDependencies(mvnExecutable, workspaceRoot, moduleDir, moduleName, progress);

            LOG.infof("Resolving %d dependencies in %s (%d reactor modules in workspace)",
                    dependencies.size(), moduleName, reactorModules.size());

            // 1. Resolve each dependency: reactor module OR external JAR
            List<String> classpathJars = new ArrayList<>();
            List<ReactorModule> reactorDeps = new ArrayList<>();
            Path m2Repo = getLocalRepository();
            int resolved = 0;
            int skipped = 0;

            for (MavenDependency dep : dependencies) {
                Path reactorPath = reactorModules.get(dep.artifactId);
                if (reactorPath != null && Files.exists(reactorPath.resolve("pom.xml"))) {
                    reactorDeps.add(new ReactorModule(dep.artifactId,
                            reactorPath.toAbsolutePath().toString()));
                } else {
                    Path jarPath = resolveJarInLocalRepo(m2Repo, dep);
                    if (jarPath != null && Files.exists(jarPath)) {
                        classpathJars.add(jarPath.toString());
                        resolved++;
                    } else {
                        LOG.warnf("JAR not found for %s:%s:%s", dep.groupId, dep.artifactId, dep.version);
                        skipped++;
                    }
                }
            }

            LOG.infof("Classpath resolved: %d JARs, %d reactor modules, %d skipped",
                    resolved, reactorDeps.size(), skipped);

            List<String> sourceRoots = detectSourceRoots(moduleDir);
            return new ClasspathInfo(moduleName, moduleDir.toAbsolutePath().toString(),
                    sourceRoots, classpathJars, reactorDeps, List.copyOf(buildFiles));

        } catch (Exception e) {
            throw new ClasspathExtractionException(
                    "Failed to extract classpath from POM for " + moduleName, e);
        }
    }

    /**
     * Downloads dependencies via {@code mvn dependency:resolve} (best-effort).
     */
    private void downloadDependencies(Path mvnExecutable, Path workspaceRoot,
                                       Path moduleDir, String moduleName,
                                       ProgressMonitor progress) {
        boolean isSubModule = !workspaceRoot.equals(moduleDir);

        List<String> args = new ArrayList<>(List.of("dependency:resolve"));
        if (isSubModule) {
            args.add("-pl");
            args.add(":" + moduleName);
        }
        args.add("-B");

        ProcessBuilder pb = createProcess(mvnExecutable, workspaceRoot,
                args.toArray(String[]::new));
        LOG.infof("Downloading dependencies: %s", String.join(" ", pb.command()));
        progress.reportProgress("Downloading dependencies for " + moduleName + " (dependency:resolve)...");

        long start = System.currentTimeMillis();
        try {
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debugf("Maven: %s", line);
                    if (!line.isBlank()) {
                        progress.reportTrace(line);
                    }
                }
            }

            int exitCode = process.waitFor();
            long elapsed = System.currentTimeMillis() - start;
            if (exitCode != 0) {
                LOG.warnf("mvn dependency:resolve failed (exit %d) in %d ms, continuing with local repository",
                        exitCode, elapsed);
                progress.reportProgress(String.format("dependency:resolve failed in %d ms", elapsed));
            } else {
                LOG.infof("Dependencies downloaded successfully in %d ms", elapsed);
                progress.reportProgress(String.format("Dependencies downloaded in %d ms", elapsed));
            }
        } catch (IOException e) {
            LOG.warnf(e, "Failed to run mvn dependency:resolve, continuing with local repository");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("mvn dependency:resolve interrupted, continuing with local repository");
        }
    }

    /**
     * Scans the workspace root {@code pom.xml} and any nested aggregator POMs recursively
     * to build a map of all reactor modules.
     *
     * <p>Each entry maps the module's {@code artifactId} to its directory on disk.
     * This is used to distinguish reactor module dependencies (set up as JDT source projects)
     * from external dependencies (resolved as JARs from the local repository).</p>
     *
     * @param workspaceRoot the root directory containing the top-level {@code pom.xml}
     * @return a map of {@code artifactId → directory} for every module in the reactor
     */
    Map<String, Path> scanReactorModules(Path workspaceRoot) {
        Map<String, Path> modules = new HashMap<>();
        scanModulesRecursive(workspaceRoot, modules);
        return modules;
    }

    private void scanModulesRecursive(Path dir, Map<String, Path> modules) {
        Path pom = dir.resolve("pom.xml");
        if (!Files.exists(pom)) {
            return;
        }

        PomInfo info = parsePomSax(pom);
        if (info == null) {
            return;
        }

        if (info.artifactId != null) {
            modules.put(info.artifactId, dir);
        }

        for (String modulePath : info.moduleNames) {
            Path subDir = dir.resolve(modulePath);
            if (Files.isDirectory(subDir)) {
                scanModulesRecursive(subDir, modules);
            }
        }
    }

    /**
     * Parses the direct dependencies declared in a {@code pom.xml}, resolving any
     * {@code ${property}} placeholders using the full parent POM hierarchy.
     *
     * <p>Only compile-scoped and runtime-scoped dependencies are returned;
     * test, provided, and system scopes are excluded.</p>
     *
     * <p>As a side-effect, every POM file consulted during property resolution
     * (the module POM and all its ancestors) is added to {@code buildFiles}
     * for cache invalidation tracking.</p>
     *
     * @param pomFile       the POM file to parse dependencies from
     * @param workspaceRoot the workspace root (parent chain stops at this boundary)
     * @param buildFiles    accumulator for all build files consulted (modified in-place)
     * @return the list of resolved dependencies (groupId, artifactId, version)
     */
    List<MavenDependency> parseDependencies(Path pomFile, Path workspaceRoot,
                                                      Set<String> buildFiles) {
        Map<String, String> properties = new HashMap<>();
        Map<String, String> managedVersions = new HashMap<>();
        collectPropertiesFromHierarchy(pomFile, workspaceRoot, properties, managedVersions, buildFiles);

        PomInfo info = parsePomSax(pomFile);
        if (info == null) {
            return List.of();
        }

        List<MavenDependency> resolved = new ArrayList<>();
        for (MavenDependency dep : info.dependencies) {
            String groupId = resolveProperty(dep.groupId, properties);
            String artifactId = resolveProperty(dep.artifactId, properties);
            String version = resolveProperty(dep.version, properties);
            // Fallback to dependencyManagement version from parent hierarchy
            if (version == null && groupId != null && artifactId != null) {
                String managedVersion = managedVersions.get(groupId + ":" + artifactId);
                if (managedVersion != null) {
                    version = resolveProperty(managedVersion, properties);
                }
            }
            if (groupId != null && artifactId != null) {
                resolved.add(new MavenDependency(groupId, artifactId, version));
            }
        }
        return resolved;
    }

    /**
     * Walks the parent POM chain starting from {@code pomFile} and collects all
     * {@code <properties>} into a merged map, following Maven's override semantics
     * (child properties win over parent properties).
     *
     * <p>The parent chain is resolved via {@code <parent><relativePath>}; if omitted,
     * the default {@code ".."} is assumed. The walk stops at the workspace root boundary
     * to avoid escaping the project.</p>
     *
     * <p>Each POM file visited is added to {@code buildFiles} for cache invalidation.
     * This includes sibling parent POMs (e.g., {@code ../bom/pom.xml}) that would be
     * missed by a simple ancestor-directory walk.</p>
     *
     * @param pomFile       the starting POM file
     * @param workspaceRoot the workspace root (boundary for parent chain resolution)
     * @param properties    accumulator for merged properties (modified in-place)
     * @param buildFiles    accumulator for all POM files visited (modified in-place)
     */
    void collectPropertiesFromHierarchy(Path pomFile, Path workspaceRoot,
                                                  Map<String, String> properties,
                                                  Set<String> buildFiles) {
        collectPropertiesFromHierarchy(pomFile, workspaceRoot, properties, new HashMap<>(),
                buildFiles, new LinkedHashSet<>());
    }

    /**
     * Overload that also collects {@code <dependencyManagement>} version mappings
     * from the parent POM hierarchy for version fallback resolution.
     *
     * @param managedVersions accumulator for managed versions ({@code groupId:artifactId → version})
     */
    void collectPropertiesFromHierarchy(Path pomFile, Path workspaceRoot,
                                                  Map<String, String> properties,
                                                  Map<String, String> managedVersions,
                                                  Set<String> buildFiles) {
        collectPropertiesFromHierarchy(pomFile, workspaceRoot, properties, managedVersions,
                buildFiles, new LinkedHashSet<>());
    }

    private void collectPropertiesFromHierarchy(Path pomFile, Path workspaceRoot,
                                                Map<String, String> properties,
                                                Map<String, String> managedVersions,
                                                Set<String> buildFiles,
                                                Set<Path> visited) {
        Path normalized = pomFile.toAbsolutePath().normalize();
        if (!visited.add(normalized)) {
            LOG.debugf("Cycle detected in parent POM chain at: %s", normalized);
            return;
        }

        PomInfo info = parsePomSax(pomFile);
        if (info == null) {
            return;
        }

        buildFiles.add(normalized.toString());

        // Resolve parent POM first (so child properties override)
        if (info.parentRelativePath != null || info.hasParent) {
            String relativePath = info.parentRelativePath != null ? info.parentRelativePath : "..";
            Path parentDir = pomFile.getParent().resolve(relativePath);
            Path parentPom = Files.isDirectory(parentDir)
                    ? parentDir.resolve("pom.xml") : parentDir;
            if (Files.exists(parentPom) && parentPom.startsWith(workspaceRoot)) {
                collectPropertiesFromHierarchy(parentPom, workspaceRoot, properties,
                        managedVersions, buildFiles, visited);
            }
        }

        if (info.version != null) {
            properties.put("project.version", info.version);
        }
        if (info.groupId != null) {
            properties.put("project.groupId", info.groupId);
        }
        properties.putAll(info.properties);

        // Child managedVersions override parent (putAll after parent recursion)
        managedVersions.putAll(info.managedVersions);
    }

    /**
     * Resolves Maven property placeholders ({@code ${...}}) in the given value
     * using the provided property map.
     *
     * <p>Handles nested property references (e.g., {@code ${${prefix}.version}})
     * with a maximum resolution depth of 10 to prevent infinite loops from
     * circular references.</p>
     *
     * @param value      the string potentially containing {@code ${property}} placeholders,
     *                   or {@code null}
     * @param properties the property map to resolve against
     * @return the resolved string, or {@code null} if the input was {@code null}.
     *         Unresolvable placeholders are left as-is.
     */
    String resolveProperty(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        int maxDepth = 10;
        while (value.contains("${") && maxDepth-- > 0) {
            int start = value.indexOf("${");
            int end = value.indexOf("}", start);
            if (end < 0) break;
            String key = value.substring(start + 2, end);
            String resolved = properties.get(key);
            if (resolved == null) break;
            value = value.substring(0, start) + resolved + value.substring(end + 1);
        }
        return value;
    }

    /**
     * Constructs the expected path to a dependency JAR in the Maven local repository.
     *
     * <p>Follows the standard Maven repository layout:
     * {@code {m2Repo}/{groupId-as-path}/{artifactId}/{version}/{artifactId}-{version}.jar}</p>
     *
     * @param m2Repo the root of the local Maven repository (typically {@code ~/.m2/repository})
     * @param dep    the dependency to resolve
     * @return the expected JAR path, or {@code null} if the dependency has no version
     */
    Path resolveJarInLocalRepo(Path m2Repo, MavenDependency dep) {
        if (dep.version == null) {
            return null;
        }
        String groupPath = dep.groupId.replace('.', '/');
        return m2Repo.resolve(groupPath)
                .resolve(dep.artifactId)
                .resolve(dep.version)
                .resolve(dep.artifactId + "-" + dep.version + ".jar");
    }

    /**
     * Returns the path to the Maven local repository ({@code ~/.m2/repository}).
     *
     * @return the local repository path derived from the {@code user.home} system property
     */
    Path getLocalRepository() {
        String m2Home = System.getProperty("user.home");
        return Path.of(m2Home, ".m2", "repository");
    }

    /**
     * Alias for {@link #findBuildToolExecutable(Path)} — kept for test readability.
     */
    Path findMavenExecutable(Path projectRoot) {
        return findBuildToolExecutable(projectRoot);
    }

    // ---- SAX-based POM parsing ----

    /**
     * Parses a {@code pom.xml} using SAX for fast, low-memory extraction of key POM metadata.
     *
     * <p>Extracts: coordinates (groupId, artifactId, version, packaging), parent reference,
     * module declarations, direct dependencies (excluding test/provided/system scope),
     * and properties. Does <em>not</em> resolve property placeholders — that is handled
     * by {@link #collectPropertiesFromHierarchy}.</p>
     *
     * <p>External entities and DTD loading are disabled for security.</p>
     *
     * @param pomFile the POM file to parse
     * @return the parsed POM information, or {@code null} if parsing fails
     */
    PomInfo parsePomSax(Path pomFile) {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newSAXParser();

            PomSaxHandler handler = new PomSaxHandler();
            parser.parse(pomFile.toFile(), handler);
            return handler.toResult();
        } catch (Exception e) {
            LOG.debugf(e, "Failed to parse POM: %s", pomFile);
            return null;
        }
    }

    /**
     * Holds the metadata extracted from a single {@code pom.xml} by SAX parsing.
     *
     * <p>This is a raw parse result — property placeholders are <em>not</em> resolved.
     * Use {@link #parseDependencies} for fully resolved dependency information.</p>
     */
    static class PomInfo {
        String artifactId;
        String groupId;
        String version;
        String packaging;
        boolean hasParent;
        String parentRelativePath;
        final List<String> moduleNames = new ArrayList<>();
        final List<MavenDependency> dependencies = new ArrayList<>();
        final Map<String, String> properties = new HashMap<>();
        final Map<String, String> managedVersions = new HashMap<>();

        /**
         * Returns {@code true} if this POM is a reactor/aggregator POM
         * (packaging is {@code pom} and at least one {@code <module>} is declared).
         */
        boolean isReactorPom() {
            return "pom".equals(packaging) && !moduleNames.isEmpty();
        }
    }

    private static class PomSaxHandler extends DefaultHandler {

        private int depth;
        private StringBuilder text;

        // Top-level fields
        private String artifactId;
        private String groupId;
        private String version;
        private String packaging;

        // Parent
        private boolean inParent;
        private boolean hasParent;
        private String parentRelativePath;

        // Modules
        private boolean inModules;
        private final List<String> moduleNames = new ArrayList<>();

        // Properties
        private boolean inProperties;
        private String currentPropertyName;
        private final Map<String, String> properties = new HashMap<>();

        // Dependencies (only top-level <project><dependencies>, not dependencyManagement/plugins)
        private boolean inTopLevelDependencies;
        private boolean inDependency;
        private String depGroupId;
        private String depArtifactId;
        private String depVersion;
        private String depScope;
        private final List<MavenDependency> dependencies = new ArrayList<>();

        // DependencyManagement
        private boolean inDependencyManagement;
        private boolean inDmDependencies;
        private boolean inDmDependency;
        private String dmDepGroupId;
        private String dmDepArtifactId;
        private String dmDepVersion;
        private final Map<String, String> managedVersions = new HashMap<>();

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            depth++;
            text = new StringBuilder();

            if (depth == 2) {
                switch (qName) {
                    case "parent" -> { inParent = true; hasParent = true; }
                    case "properties" -> inProperties = true;
                    case "modules" -> inModules = true;
                    case "dependencies" -> inTopLevelDependencies = true;
                    case "dependencyManagement" -> inDependencyManagement = true;
                }
            } else if (depth == 3) {
                if (inTopLevelDependencies && "dependency".equals(qName)) {
                    inDependency = true;
                    depGroupId = null;
                    depArtifactId = null;
                    depVersion = null;
                    depScope = null;
                }
                if (inDependencyManagement && "dependencies".equals(qName)) {
                    inDmDependencies = true;
                }
                if (inProperties) {
                    currentPropertyName = qName;
                }
            } else if (depth == 4 && inDmDependencies && "dependency".equals(qName)) {
                inDmDependency = true;
                dmDepGroupId = null;
                dmDepArtifactId = null;
                dmDepVersion = null;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (text != null) {
                text.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String content = text != null ? text.toString().trim() : null;

            if (depth == 2) {
                switch (qName) {
                    case "artifactId" -> artifactId = content;
                    case "groupId" -> groupId = content;
                    case "version" -> version = content;
                    case "packaging" -> packaging = content;
                    case "parent" -> inParent = false;
                    case "properties" -> inProperties = false;
                    case "modules" -> inModules = false;
                    case "dependencies" -> inTopLevelDependencies = false;
                    case "dependencyManagement" -> inDependencyManagement = false;
                }
            } else if (depth == 3) {
                if (inParent && "relativePath".equals(qName)) {
                    parentRelativePath = content;
                }
                if (inModules && "module".equals(qName) && content != null && !content.isEmpty()) {
                    moduleNames.add(content);
                }
                if (inProperties && currentPropertyName != null) {
                    if (content != null) {
                        properties.put(currentPropertyName, content);
                    }
                    currentPropertyName = null;
                }
                if (inDependency && "dependency".equals(qName)) {
                    if (depGroupId != null && depArtifactId != null) {
                        if (depScope == null || "compile".equals(depScope) || "runtime".equals(depScope)) {
                            dependencies.add(new MavenDependency(depGroupId, depArtifactId, depVersion));
                        }
                    }
                    inDependency = false;
                }
                if (inDependencyManagement && "dependencies".equals(qName)) {
                    inDmDependencies = false;
                }
            } else if (depth == 4) {
                if (inDependency) {
                    switch (qName) {
                        case "groupId" -> depGroupId = content;
                        case "artifactId" -> depArtifactId = content;
                        case "version" -> depVersion = content;
                        case "scope" -> depScope = content;
                    }
                }
                if (inDmDependency && "dependency".equals(qName)) {
                    if (dmDepGroupId != null && dmDepArtifactId != null && dmDepVersion != null) {
                        managedVersions.put(dmDepGroupId + ":" + dmDepArtifactId, dmDepVersion);
                    }
                    inDmDependency = false;
                }
            } else if (depth == 5 && inDmDependency) {
                switch (qName) {
                    case "groupId" -> dmDepGroupId = content;
                    case "artifactId" -> dmDepArtifactId = content;
                    case "version" -> dmDepVersion = content;
                }
            }

            text = null;
            depth--;
        }

        PomInfo toResult() {
            PomInfo info = new PomInfo();
            info.artifactId = artifactId;
            info.groupId = groupId;
            info.version = version;
            info.packaging = packaging;
            info.hasParent = hasParent;
            info.parentRelativePath = parentRelativePath;
            info.moduleNames.addAll(moduleNames);
            info.dependencies.addAll(dependencies);
            info.properties.putAll(properties);
            info.managedVersions.putAll(managedVersions);
            return info;
        }
    }

    /**
     * Represents a single Maven dependency with resolved coordinates.
     *
     * @param groupId    the Maven group ID (e.g., {@code org.apache.commons})
     * @param artifactId the Maven artifact ID (e.g., {@code commons-lang3})
     * @param version    the resolved version string, or {@code null} if unresolvable
     */
    record MavenDependency(String groupId, String artifactId, String version) {
    }
}
