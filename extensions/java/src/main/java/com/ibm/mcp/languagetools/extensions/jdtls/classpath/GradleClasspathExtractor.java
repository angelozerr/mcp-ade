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
public class GradleClasspathExtractor extends AbstractClasspathExtractor {

    private static final Logger LOG = Logger.getLogger(GradleClasspathExtractor.class);

    private static final String CLASSPATH_PREFIX = "MCP_CLASSPATH:";
    private static final String SOURCES_PREFIX = "MCP_SOURCES:";

    @Override
    protected String unixWrapperName() { return "gradlew"; }

    @Override
    protected String windowsWrapperName() { return "gradlew.bat"; }

    @Override
    protected String unixSystemName() { return "gradle"; }

    @Override
    protected String windowsSystemName() { return "gradle.bat"; }

    @Override
    protected String buildToolOptsVar() { return "GRADLE_OPTS"; }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code true} if a {@code build.gradle} or {@code build.gradle.kts}
     * file exists at the workspace root.</p>
     */
    @Override
    public boolean canHandle(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("build.gradle"))
                || Files.exists(workspaceRoot.resolve("build.gradle.kts"));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Injects a temporary Gradle init script that registers an {@code mcpClasspath}
     * task on all projects. The task outputs the resolved compile + test classpath
     * and source directories in a parseable format. The init script is deleted after
     * execution.</p>
     *
     * <p>Build files tracked for cache invalidation include: {@code build.gradle},
     * {@code build.gradle.kts}, {@code settings.gradle}, and {@code settings.gradle.kts}.</p>
     *
     * @param workspaceRoot the root of the Gradle project
     * @param moduleDir     the directory of the specific subproject to extract classpath for
     * @param progress      progress monitor for reporting download progress
     * @return the extracted classpath information
     * @throws ClasspathExtractionException if the Gradle task fails or an I/O error occurs
     */
    @Override
    public ClasspathInfo extract(Path workspaceRoot, Path moduleDir, ProgressMonitor progress)
            throws ClasspathExtractionException {
        String moduleName = moduleDir.getFileName().toString();
        Path gradleExecutable = findBuildToolExecutable(workspaceRoot);
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

    /**
     * Alias for {@link #findBuildToolExecutable(Path)} — kept for test readability.
     */
    Path findGradleExecutable(Path projectRoot) {
        return findBuildToolExecutable(projectRoot);
    }

    private ClasspathInfo runGradleTask(Path gradleExecutable, Path workspaceRoot,
                                         Path moduleDir, String moduleName,
                                         Path initScript, ProgressMonitor progress)
            throws ClasspathExtractionException, IOException {
        boolean isSubProject = !workspaceRoot.equals(moduleDir);

        List<String> args = new ArrayList<>();
        args.add("-I");
        args.add(initScript.toAbsolutePath().toString());
        if (isSubProject) {
            args.add(":" + moduleName + ":mcpClasspath");
        } else {
            args.add("mcpClasspath");
        }
        args.add("-q");

        ProcessBuilder pb = createProcess(gradleExecutable, workspaceRoot,
                args.toArray(String[]::new));
        LOG.infof("Running: %s", String.join(" ", pb.command()));

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

        int exitCode = waitForProcess(process);

        if (exitCode != 0) {
            throw new ClasspathExtractionException(
                    "Gradle mcpClasspath task failed with exit code " + exitCode);
        }

        List<String> buildFiles = collectGradleBuildFiles(workspaceRoot, moduleDir);

        return new ClasspathInfo(
                moduleName,
                moduleDir.toAbsolutePath().toString(),
                sourceRoots,
                classpathJars,
                List.of(),
                buildFiles);
    }

    private List<String> collectGradleBuildFiles(Path workspaceRoot, Path moduleDir) {
        List<String> buildFiles = new ArrayList<>();
        for (String fileName : new String[]{
                "build.gradle", "build.gradle.kts"}) {
            Path file = moduleDir.resolve(fileName);
            if (Files.exists(file)) {
                buildFiles.add(file.toAbsolutePath().normalize().toString());
            }
        }
        for (String fileName : new String[]{
                "settings.gradle", "settings.gradle.kts"}) {
            Path file = workspaceRoot.resolve(fileName);
            if (Files.exists(file)) {
                buildFiles.add(file.toAbsolutePath().normalize().toString());
            }
        }
        return buildFiles;
    }
}
