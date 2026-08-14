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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import com.ibm.mcp.languagetools.progress.ProgressMonitor;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Extracts classpath from Gradle projects using a lightweight init script.
 *
 * <p>Prioritizes the Gradle wrapper ({@code gradlew}/{@code gradlew.bat}) if present
 * in the project root, falling back to system {@code gradle} on PATH.</p>
 */
@ApplicationScoped
public class GradleClasspathExtractor implements ClasspathExtractor {

    private static final Logger LOG = Logger.getLogger(GradleClasspathExtractor.class);

    private static final String CLASSPATH_PREFIX = "MCP_CLASSPATH:";
    private static final String SOURCES_PREFIX = "MCP_SOURCES:";

    @Override
    public boolean canHandle(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("build.gradle"))
                || Files.exists(workspaceRoot.resolve("build.gradle.kts"));
    }

    @Override
    public ClasspathInfo extract(Path workspaceRoot, Path moduleDir, ProgressMonitor progress)
            throws ClasspathExtractionException {
        String moduleName = moduleDir.getFileName().toString();
        Path gradleExecutable = findGradleExecutable(workspaceRoot);
        LOG.infof("Using Gradle executable: %s", gradleExecutable);

        try {
            Path initScript = createInitScript();
            try {
                return runGradleTask(gradleExecutable, workspaceRoot, moduleDir,
                        moduleName, initScript, progress);
            } finally {
                Files.deleteIfExists(initScript);
            }
        } catch (IOException e) {
            throw new ClasspathExtractionException(
                    "Failed to extract Gradle classpath for " + moduleName, e);
        }
    }

    private Path createInitScript() throws IOException {
        Path initScript = Files.createTempFile("mcp-gradle-init-", ".gradle");
        try (InputStream is = getClass().getResourceAsStream("/gradle/mcp-classpath-init.gradle")) {
            if (is != null) {
                Files.write(initScript, is.readAllBytes());
            } else {
                Files.writeString(initScript, getDefaultInitScript());
            }
        }
        return initScript;
    }

    private String getDefaultInitScript() {
        return """
                allprojects {
                    tasks.register("mcpClasspath") {
                        doLast {
                            def cp = ""
                            try {
                                cp = configurations.compileClasspath.resolve().join(File.pathSeparator)
                            } catch (Exception e) {
                                // compileClasspath may not exist for non-Java projects
                            }
                            def testCp = ""
                            try {
                                testCp = configurations.testCompileClasspath.resolve()
                                    .findAll { !configurations.compileClasspath.resolve().contains(it) }
                                    .join(File.pathSeparator)
                            } catch (Exception e) {
                                // testCompileClasspath may not exist
                            }
                            def allCp = [cp, testCp].findAll { it }.join(File.pathSeparator)
                            println "MCP_CLASSPATH:" + allCp
                            def srcDirs = []
                            try {
                                srcDirs = sourceSets.main.java.srcDirs.collect { it.absolutePath }
                                srcDirs += sourceSets.test.java.srcDirs.collect { it.absolutePath }
                            } catch (Exception e) {
                                // sourceSets may not exist
                            }
                            println "MCP_SOURCES:" + srcDirs.join(File.pathSeparator)
                        }
                    }
                }
                """;
    }

    private ClasspathInfo runGradleTask(Path gradleExecutable, Path workspaceRoot,
                                         Path moduleDir, String moduleName,
                                         Path initScript, ProgressMonitor progress)
            throws ClasspathExtractionException, IOException {
        boolean isSubProject = !workspaceRoot.equals(moduleDir);

        List<String> command = new ArrayList<>();
        command.add(gradleExecutable.toAbsolutePath().toString());
        command.add("-I");
        command.add(initScript.toAbsolutePath().toString());
        if (isSubProject) {
            command.add(":" + moduleName + ":mcpClasspath");
        } else {
            command.add("mcpClasspath");
        }
        command.add("-q");

        LOG.infof("Running: %s", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspaceRoot.toFile());
        pb.redirectErrorStream(true);
        cleanDebugEnvironment(pb);

        Process process = pb.start();

        List<String> classpathJars = List.of();
        List<String> sourceRoots = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(CLASSPATH_PREFIX)) {
                    String cp = line.substring(CLASSPATH_PREFIX.length()).trim();
                    if (!cp.isEmpty()) {
                        classpathJars = Arrays.stream(cp.split(File.pathSeparator))
                                .filter(entry -> entry.endsWith(".jar"))
                                .toList();
                    }
                } else if (line.startsWith(SOURCES_PREFIX)) {
                    String src = line.substring(SOURCES_PREFIX.length()).trim();
                    if (!src.isEmpty()) {
                        for (String srcDir : src.split(File.pathSeparator)) {
                            Path srcPath = Path.of(srcDir);
                            if (Files.isDirectory(srcPath)) {
                                sourceRoots.add(
                                        moduleDir.relativize(srcPath).toString().replace('\\', '/'));
                            }
                        }
                    }
                } else if (line.startsWith("Downloading") || line.startsWith("Download")) {
                    progress.reportProgress(line);
                }
                LOG.debugf("Gradle: %s", line);
            }
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClasspathExtractionException("Gradle process interrupted");
        }

        if (exitCode != 0) {
            throw new ClasspathExtractionException(
                    "Gradle mcpClasspath task failed with exit code " + exitCode);
        }

        List<String> buildFiles = new ArrayList<>();
        Path gradleBuild = moduleDir.resolve("build.gradle");
        if (Files.exists(gradleBuild)) {
            buildFiles.add(gradleBuild.toAbsolutePath().normalize().toString());
        }
        Path gradleBuildKts = moduleDir.resolve("build.gradle.kts");
        if (Files.exists(gradleBuildKts)) {
            buildFiles.add(gradleBuildKts.toAbsolutePath().normalize().toString());
        }
        Path settingsGradle = workspaceRoot.resolve("settings.gradle");
        if (Files.exists(settingsGradle)) {
            buildFiles.add(settingsGradle.toAbsolutePath().normalize().toString());
        }
        Path settingsGradleKts = workspaceRoot.resolve("settings.gradle.kts");
        if (Files.exists(settingsGradleKts)) {
            buildFiles.add(settingsGradleKts.toAbsolutePath().normalize().toString());
        }

        return new ClasspathInfo(
                moduleName,
                moduleDir.toAbsolutePath().toString(),
                sourceRoots,
                classpathJars,
                List.of(),
                buildFiles);
    }

    private static void cleanDebugEnvironment(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        env.remove("JAVA_TOOL_OPTIONS");
        env.remove("_JAVA_OPTIONS");
        String gradleOpts = env.get("GRADLE_OPTS");
        if (gradleOpts != null) {
            String cleaned = gradleOpts
                    .replaceAll("-agentlib:jdwp\\S*", "")
                    .replaceAll("-javaagent:\\S*", "")
                    .trim();
            if (cleaned.isEmpty()) {
                env.remove("GRADLE_OPTS");
            } else {
                env.put("GRADLE_OPTS", cleaned);
            }
        }
    }

    private Path findGradleExecutable(Path projectRoot) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapperName = isWindows ? "gradlew.bat" : "gradlew";
        Path wrapper = projectRoot.resolve(wrapperName);
        if (Files.isRegularFile(wrapper)) {
            return wrapper;
        }
        return Path.of(isWindows ? "gradle.bat" : "gradle");
    }
}
