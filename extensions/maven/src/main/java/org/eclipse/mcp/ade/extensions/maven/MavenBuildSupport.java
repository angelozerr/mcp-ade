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
package org.eclipse.mcp.ade.extensions.maven;

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

import org.jboss.logging.Logger;

import org.eclipse.mcp.ade.extensions.jdtls.build.AbstractBuildSupport;
import org.eclipse.mcp.ade.extensions.jdtls.build.BuildSupportException;
import org.eclipse.mcp.ade.extensions.jdtls.build.ClasspathInfo;
import org.eclipse.mcp.ade.extensions.jdtls.build.ClasspathInfo.ReactorModule;
import org.eclipse.mcp.ade.progress.ProgressMonitor;

/**
 * Extracts classpath from Maven projects.
 *
 * <p>Strategy (tried in order):
 * <ol>
 *   <li>{@code mvn dependency:build-classpath} — fast, works if all deps are in {@code ~/.m2}</li>
 *   <li>{@code mvn dependency:resolve} then retry {@code dependency:build-classpath}
 *       — downloads missing JARs first, then uses Maven for full classpath resolution</li>
 *   <li>POM parsing — last resort when Maven fails entirely (reactor SNAPSHOTs);
 *       builds classpath manually from {@code ~/.m2/repository}</li>
 * </ol>
 *
 * <p>Prioritizes the Maven wrapper ({@code mvnw}/{@code mvnw.cmd}) if present
 * in the project root, falling back to system {@code mvn} on PATH.</p>
 */
public class MavenBuildSupport extends AbstractBuildSupport {

    private static final Logger LOG = Logger.getLogger(MavenBuildSupport.class);

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

    @Override
    public List<String> discoverSubModules(Path parentDir) {
        return PomParser.parseModules(parentDir.resolve("pom.xml"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Extraction proceeds through three strategies, tried in order:
     * <ol>
     *   <li><strong>Strategy 1</strong> — {@code mvn dependency:build-classpath}:
     *       fast and reliable when all dependencies are installed in the local repository.</li>
     *   <li><strong>Strategy 2</strong> — {@code mvn dependency:resolve} then retry
     *       {@code dependency:build-classpath}: downloads missing JARs, then delegates
     *       classpath resolution to Maven for full correctness (profiles, BOMs, exclusions).</li>
     *   <li><strong>Strategy 3</strong> — POM-based resolution (last resort): parses dependency
     *       declarations from the POM hierarchy, resolves JARs from {@code ~/.m2/repository},
     *       and identifies reactor module dependencies as source project references.</li>
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
     * @throws BuildSupportException if no {@code pom.xml} exists in {@code moduleDir}
     *                                      or both strategies fail
     */
    @Override
    public ClasspathInfo extract(Path workspaceRoot, Path moduleDir, ProgressMonitor progress)
            throws BuildSupportException {
        Path pomFile = moduleDir.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            throw new BuildSupportException(
                    "No pom.xml found in module directory: " + moduleDir);
        }

        PomParser.PomInfo pomInfo = parsePomSax(pomFile);
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
        List<PomParser.MavenDependency> dependencies = parseDependencies(pomFile, workspaceRoot, buildFiles);

        List<ReactorModule> reactorDeps = new ArrayList<>();
        boolean allReactor = true;
        for (PomParser.MavenDependency dep : dependencies) {
            Path reactorPath = reactorModules.get(dep.artifactId());
            if (reactorPath != null && Files.exists(reactorPath.resolve("pom.xml"))) {
                reactorDeps.add(new ReactorModule(dep.artifactId(),
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
        } catch (BuildSupportException e) {
            LOG.infof("dependency:build-classpath failed, falling back to POM parsing: %s", e.getMessage());
        }

        // Strategy 2: dependency:resolve + retry dependency:build-classpath
        progress.reportProgress("Downloading dependencies for " + moduleName + " (dependency:resolve)...");
        start = System.currentTimeMillis();
        downloadDependencies(mvnExecutable, workspaceRoot, moduleDir, moduleName, progress);
        long resolveElapsed = System.currentTimeMillis() - start;

        try {
            progress.reportProgress("Retrying dependency:build-classpath after resolve...");
            start = System.currentTimeMillis();
            ClasspathInfo result = tryBuildClasspath(mvnExecutable, workspaceRoot, moduleDir,
                    moduleName, buildFiles, progress);
            if (result != null) {
                long elapsed = System.currentTimeMillis() - start;
                LOG.infof("Strategy 2 (resolve + build-classpath) completed in %d ms (resolve: %d ms, classpath: %d ms)",
                        resolveElapsed + elapsed, resolveElapsed, elapsed);
                progress.reportProgress(String.format("Classpath resolved after download in %d ms (%d JARs)",
                        resolveElapsed + elapsed, result.classpathJars().size()));
                return result;
            }
        } catch (BuildSupportException e) {
            LOG.infof("dependency:build-classpath failed after resolve, falling back to POM parsing: %s", e.getMessage());
        }

        // Strategy 3: POM-based resolution (last resort — handles reactor SNAPSHOTs)
        progress.reportProgress("Resolving classpath for " + moduleName + " from POM (reactor mode)...");
        start = System.currentTimeMillis();
        ClasspathInfo result = extractFromPom(workspaceRoot, moduleDir, moduleName,
                reactorModules, dependencies, buildFiles, progress);
        long elapsed = System.currentTimeMillis() - start;
        LOG.infof("Strategy 3 (POM parsing) completed in %d ms", elapsed);
        progress.reportProgress(String.format("Classpath resolved via POM parsing in %d ms (%d JARs, %d reactor modules)",
                elapsed, result.classpathJars().size(), result.reactorModuleDeps().size()));
        return result;
    }

    // ---- Strategy 1: mvn dependency:build-classpath ----

    private ClasspathInfo tryBuildClasspath(Path mvnExecutable, Path workspaceRoot,
                                             Path moduleDir, String moduleName,
                                             Set<String> buildFiles,
                                             ProgressMonitor progress)
            throws BuildSupportException {
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
            throw new BuildSupportException("IO error during build-classpath", e);
        }
    }

    private List<String> runBuildClasspath(Path mvnExecutable, Path workspaceRoot,
                                           Path moduleDir, String moduleName,
                                           Path outputFile,
                                           ProgressMonitor progress)
            throws BuildSupportException, IOException {
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
            throw new BuildSupportException(
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

        return Arrays.stream(classpath.split(File.pathSeparator))
                .filter(entry -> entry.endsWith(".jar"))
                .toList();
    }

    // ---- Strategy 2: POM-based resolution ----

    private ClasspathInfo extractFromPom(Path workspaceRoot, Path moduleDir,
                                          String moduleName,
                                          Map<String, Path> reactorModules,
                                          List<PomParser.MavenDependency> dependencies,
                                          Set<String> buildFiles,
                                          ProgressMonitor progress)
            throws BuildSupportException {
        try {
            LOG.infof("Resolving %d dependencies in %s (%d reactor modules in workspace)",
                    dependencies.size(), moduleName, reactorModules.size());

            List<String> classpathJars = new ArrayList<>();
            List<ReactorModule> reactorDeps = new ArrayList<>();
            Path m2Repo = getLocalRepository();
            int resolved = 0;
            int skipped = 0;

            for (PomParser.MavenDependency dep : dependencies) {
                Path reactorPath = reactorModules.get(dep.artifactId());
                if (reactorPath != null && Files.exists(reactorPath.resolve("pom.xml"))) {
                    reactorDeps.add(new ReactorModule(dep.artifactId(),
                            reactorPath.toAbsolutePath().toString()));
                } else {
                    Path jarPath = resolveJarInLocalRepo(m2Repo, dep);
                    if (jarPath != null && Files.exists(jarPath)) {
                        classpathJars.add(jarPath.toString());
                        resolved++;
                    } else {
                        LOG.warnf("JAR not found for %s:%s:%s", dep.groupId(), dep.artifactId(), dep.version());
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
            throw new BuildSupportException(
                    "Failed to extract classpath from POM for " + moduleName, e);
        }
    }

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

        PomParser.PomInfo info = parsePomSax(pom);
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

    List<PomParser.MavenDependency> parseDependencies(Path pomFile, Path workspaceRoot,
                                                      Set<String> buildFiles) {
        Map<String, String> properties = new HashMap<>();
        Map<String, String> managedVersions = new HashMap<>();
        collectPropertiesFromHierarchy(pomFile, workspaceRoot, properties, managedVersions, buildFiles);

        PomParser.PomInfo info = parsePomSax(pomFile);
        if (info == null) {
            return List.of();
        }

        List<PomParser.MavenDependency> resolved = new ArrayList<>();
        for (PomParser.MavenDependency dep : info.dependencies) {
            String groupId = resolveProperty(dep.groupId(), properties);
            String artifactId = resolveProperty(dep.artifactId(), properties);
            String version = resolveProperty(dep.version(), properties);
            if (version == null && groupId != null && artifactId != null) {
                String managedVersion = managedVersions.get(groupId + ":" + artifactId);
                if (managedVersion != null) {
                    version = resolveProperty(managedVersion, properties);
                }
            }
            if (groupId != null && artifactId != null) {
                resolved.add(new PomParser.MavenDependency(groupId, artifactId, version));
            }
        }
        return resolved;
    }

    void collectPropertiesFromHierarchy(Path pomFile, Path workspaceRoot,
                                                  Map<String, String> properties,
                                                  Set<String> buildFiles) {
        collectPropertiesFromHierarchy(pomFile, workspaceRoot, properties, new HashMap<>(),
                buildFiles, new LinkedHashSet<>());
    }

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

        PomParser.PomInfo info = parsePomSax(pomFile);
        if (info == null) {
            return;
        }

        buildFiles.add(normalized.toString());

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

        managedVersions.putAll(info.managedVersions);
    }

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

    Path resolveJarInLocalRepo(Path m2Repo, PomParser.MavenDependency dep) {
        if (dep.version() == null) {
            return null;
        }
        String groupPath = dep.groupId().replace('.', '/');
        return m2Repo.resolve(groupPath)
                .resolve(dep.artifactId())
                .resolve(dep.version())
                .resolve(dep.artifactId() + "-" + dep.version() + ".jar");
    }

    Path getLocalRepository() {
        String m2Home = System.getProperty("user.home");
        return Path.of(m2Home, ".m2", "repository");
    }

    Path findMavenExecutable(Path projectRoot) {
        return findBuildToolExecutable(projectRoot);
    }

    // ---- SAX-based POM parsing ----

    PomParser.PomInfo parsePomSax(Path pomFile) {
        PomParser.PomInfo info = PomParser.parseFull(pomFile);
        if (info == null) {
            LOG.debugf("Failed to parse POM: %s", pomFile);
        }
        return info;
    }

}
