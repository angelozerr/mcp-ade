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
import java.util.List;
import java.util.Map;

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
public class MavenClasspathExtractor implements ClasspathExtractor {

    private static final Logger LOG = Logger.getLogger(MavenClasspathExtractor.class);

    @Override
    public boolean canHandle(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("pom.xml"));
    }

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
                    sourceRoots, List.of(), List.of());
        }

        // Fast path: if ALL dependencies are reactor modules, skip Maven entirely
        long start = System.currentTimeMillis();
        Map<String, Path> reactorModules = scanReactorModules(workspaceRoot);
        List<MavenDependency> dependencies = parseDependencies(pomFile, workspaceRoot);

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
                    sourceRoots, List.of(), reactorDeps);
        }

        Path mvnExecutable = findMavenExecutable(workspaceRoot);
        LOG.infof("Using Maven executable: %s", mvnExecutable);

        // Strategy 1: try mvn dependency:build-classpath (fast path)
        try {
            progress.reportProgress("Resolving classpath for " + moduleName + " via Maven (dependency:build-classpath)...");
            start = System.currentTimeMillis();
            ClasspathInfo result = tryBuildClasspath(mvnExecutable, workspaceRoot, moduleDir, moduleName, progress);
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
        ClasspathInfo result = extractFromPom(workspaceRoot, moduleDir, moduleName, progress);
        long elapsed = System.currentTimeMillis() - start;
        LOG.infof("Strategy 2 (POM parsing) completed in %d ms", elapsed);
        progress.reportProgress(String.format("Classpath resolved via POM parsing in %d ms (%d JARs, %d reactor modules)",
                elapsed, result.classpathJars().size(), result.reactorModuleDeps().size()));
        return result;
    }

    // ---- Strategy 1: mvn dependency:build-classpath ----

    private ClasspathInfo tryBuildClasspath(Path mvnExecutable, Path workspaceRoot,
                                             Path moduleDir, String moduleName,
                                             ProgressMonitor progress)
            throws ClasspathExtractionException {
        try {
            Path tempFile = Files.createTempFile("mcp-classpath-", ".txt");
            try {
                List<String> classpathJars = runBuildClasspath(
                        mvnExecutable, workspaceRoot, moduleDir, moduleName, tempFile, progress);
                List<String> sourceRoots = detectSourceRoots(moduleDir);
                return new ClasspathInfo(moduleName, moduleDir.toAbsolutePath().toString(),
                        sourceRoots, classpathJars, List.of());
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

        List<String> command = new ArrayList<>();
        String executable = mvnExecutable.toString();
        if (executable.endsWith(".cmd") || executable.endsWith(".bat")) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(executable);
        command.add("dependency:build-classpath");
        command.add("-DincludeScope=test");
        command.add("-Dmdep.outputFile=" + outputFile.toAbsolutePath());
        if (isSubModule) {
            command.add("-pl");
            command.add(":" + moduleName);
        }
        command.add("-B");

        LOG.infof("Running: %s", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspaceRoot.toFile());
        pb.redirectErrorStream(true);
        cleanDebugEnvironment(pb);

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

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClasspathExtractionException("Maven process interrupted");
        }

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
                                          String moduleName, ProgressMonitor progress)
            throws ClasspathExtractionException {
        try {
            // 0. Download dependencies first (best-effort)
            Path mvnExecutable = findMavenExecutable(workspaceRoot);
            downloadDependencies(mvnExecutable, workspaceRoot, moduleDir, moduleName, progress);

            // 1. Build a map of all reactor modules: artifactId -> directory path
            Map<String, Path> reactorModules = scanReactorModules(workspaceRoot);
            LOG.infof("Found %d reactor modules in workspace", reactorModules.size());

            // 2. Parse the target module's POM for dependencies
            Path pomFile = moduleDir.resolve("pom.xml");
            List<MavenDependency> dependencies = parseDependencies(pomFile, workspaceRoot);
            LOG.infof("Found %d dependencies in %s", dependencies.size(), moduleName);

            // 3. Resolve each dependency: reactor module OR external JAR
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
                    sourceRoots, classpathJars, reactorDeps);

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

        List<String> command = new ArrayList<>();
        String executable = mvnExecutable.toString();
        if (executable.endsWith(".cmd") || executable.endsWith(".bat")) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(executable);
        command.add("dependency:resolve");
        if (isSubModule) {
            command.add("-pl");
            command.add(":" + moduleName);
        }
        command.add("-B");

        LOG.infof("Downloading dependencies: %s", String.join(" ", command));
        progress.reportProgress("Downloading dependencies for " + moduleName + " (dependency:resolve)...");

        long start = System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workspaceRoot.toFile());
            pb.redirectErrorStream(true);
            cleanDebugEnvironment(pb);

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
     * Scans the workspace root pom.xml (and sub-aggregators) to find all reactor modules.
     */
    private Map<String, Path> scanReactorModules(Path workspaceRoot) {
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
     * Parses dependencies from a pom.xml, resolving properties from parent POMs.
     */
    private List<MavenDependency> parseDependencies(Path pomFile, Path workspaceRoot) {
        Map<String, String> properties = new HashMap<>();
        collectPropertiesFromHierarchy(pomFile, workspaceRoot, properties);

        PomInfo info = parsePomSax(pomFile);
        if (info == null) {
            return List.of();
        }

        List<MavenDependency> resolved = new ArrayList<>();
        for (MavenDependency dep : info.dependencies) {
            String groupId = resolveProperty(dep.groupId, properties);
            String artifactId = resolveProperty(dep.artifactId, properties);
            String version = resolveProperty(dep.version, properties);
            if (groupId != null && artifactId != null) {
                resolved.add(new MavenDependency(groupId, artifactId, version));
            }
        }
        return resolved;
    }

    private void collectPropertiesFromHierarchy(Path pomFile, Path workspaceRoot,
                                                  Map<String, String> properties) {
        PomInfo info = parsePomSax(pomFile);
        if (info == null) {
            return;
        }

        // Resolve parent POM first (so child properties override)
        if (info.parentRelativePath != null || info.hasParent) {
            String relativePath = info.parentRelativePath != null ? info.parentRelativePath : "..";
            Path parentDir = pomFile.getParent().resolve(relativePath);
            Path parentPom = Files.isDirectory(parentDir)
                    ? parentDir.resolve("pom.xml") : parentDir;
            if (Files.exists(parentPom) && parentPom.startsWith(workspaceRoot)) {
                collectPropertiesFromHierarchy(parentPom, workspaceRoot, properties);
            }
        }

        if (info.version != null) {
            properties.put("project.version", info.version);
        }
        if (info.groupId != null) {
            properties.put("project.groupId", info.groupId);
        }
        properties.putAll(info.properties);
    }

    private String resolveProperty(String value, Map<String, String> properties) {
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

    private Path resolveJarInLocalRepo(Path m2Repo, MavenDependency dep) {
        if (dep.version == null) {
            return null;
        }
        String groupPath = dep.groupId.replace('.', '/');
        return m2Repo.resolve(groupPath)
                .resolve(dep.artifactId)
                .resolve(dep.version)
                .resolve(dep.artifactId + "-" + dep.version + ".jar");
    }

    private Path getLocalRepository() {
        String m2Home = System.getProperty("user.home");
        return Path.of(m2Home, ".m2", "repository");
    }

    // ---- Environment cleanup ----

    private static void cleanDebugEnvironment(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.remove("JAVA_TOOL_OPTIONS");
        env.remove("_JAVA_OPTIONS");
        String mavenOpts = env.get("MAVEN_OPTS");
        if (mavenOpts != null) {
            String cleaned = mavenOpts
                    .replaceAll("-agentlib:jdwp\\S*", "")
                    .replaceAll("-javaagent:\\S*", "")
                    .trim();
            if (cleaned.isEmpty()) {
                env.remove("MAVEN_OPTS");
            } else {
                env.put("MAVEN_OPTS", cleaned);
            }
        }
    }

    // ---- Source root detection ----

    private List<String> detectSourceRoots(Path moduleDir) {
        List<String> roots = new ArrayList<>();
        String[] candidates = {
                "src/main/java",
                "src/main/resources",
                "src/test/java",
                "src/test/resources"
        };
        for (String candidate : candidates) {
            if (Files.isDirectory(moduleDir.resolve(candidate))) {
                roots.add(candidate);
            }
        }
        if (roots.isEmpty() && Files.isDirectory(moduleDir.resolve("src"))) {
            roots.add("src");
        }
        return roots;
    }

    // ---- Maven executable detection ----

    private Path findMavenExecutable(Path projectRoot) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapperName = isWindows ? "mvnw.cmd" : "mvnw";
        Path dir = projectRoot.toAbsolutePath().normalize();
        while (dir != null) {
            Path wrapper = dir.resolve(wrapperName);
            if (Files.isRegularFile(wrapper)) {
                return wrapper;
            }
            dir = dir.getParent();
        }
        return Path.of(isWindows ? "mvn.cmd" : "mvn");
    }

    // ---- SAX-based POM parsing ----

    private PomInfo parsePomSax(Path pomFile) {
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

    private static class PomInfo {
        String artifactId;
        String groupId;
        String version;
        String packaging;
        boolean hasParent;
        String parentRelativePath;
        final List<String> moduleNames = new ArrayList<>();
        final List<MavenDependency> dependencies = new ArrayList<>();
        final Map<String, String> properties = new HashMap<>();

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
                }
            } else if (depth == 3) {
                if (inTopLevelDependencies && "dependency".equals(qName)) {
                    inDependency = true;
                    depGroupId = null;
                    depArtifactId = null;
                    depVersion = null;
                    depScope = null;
                }
                if (inProperties) {
                    currentPropertyName = qName;
                }
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
            } else if (depth == 4 && inDependency) {
                switch (qName) {
                    case "groupId" -> depGroupId = content;
                    case "artifactId" -> depArtifactId = content;
                    case "version" -> depVersion = content;
                    case "scope" -> depScope = content;
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
            return info;
        }
    }

    private record MavenDependency(String groupId, String artifactId, String version) {
    }
}
