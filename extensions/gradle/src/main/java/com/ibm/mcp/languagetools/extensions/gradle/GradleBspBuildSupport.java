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
package com.ibm.mcp.languagetools.extensions.gradle;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.jboss.logging.Logger;

import ch.epfl.scala.bsp4j.BuildTarget;
import ch.epfl.scala.bsp4j.BuildTargetIdentifier;
import ch.epfl.scala.bsp4j.JvmCompileClasspathParams;
import ch.epfl.scala.bsp4j.SourceItem;
import ch.epfl.scala.bsp4j.SourceItemKind;
import ch.epfl.scala.bsp4j.SourcesParams;

import com.ibm.mcp.languagetools.bsp.server.BspServer;
import com.ibm.mcp.languagetools.extensions.jdtls.build.BspBuildSupport;
import com.ibm.mcp.languagetools.extensions.jdtls.build.ClasspathInfo;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;

/**
 * Extracts classpath from Gradle projects via the Build Server Protocol (BSP).
 *
 * <p>Uses the {@code build-server-for-gradle} BSP server to query build targets,
 * source directories, and JVM compile classpath. All BSP calls are fully async
 * (no {@code join()} or {@code get()}).</p>
 */
public class GradleBspBuildSupport implements BspBuildSupport {

    private static final Logger LOG = Logger.getLogger(GradleBspBuildSupport.class);

    private static final String BSP_SERVER_ID = "build-server-for-gradle";

    @Override
    public String getBspServerId() {
        return BSP_SERVER_ID;
    }

    @Override
    public CompletableFuture<ClasspathInfo> extractAsync(BspServer bspServer,
                                                         Path workspaceRoot, Path moduleDir,
                                                         ProgressMonitor progress) {
        String moduleName = moduleDir.getFileName().toString();
        LOG.infof("BSP: extracting classpath for module %s", moduleName);
        progress.reportProgress("BSP: resolving build targets for " + moduleName);

        return bspServer.getBuildServer().workspaceBuildTargets()
                .thenCompose(targetsResult -> {
                    List<BuildTargetIdentifier> matchingTargets =
                            findTargetsForModule(targetsResult.getTargets(), moduleDir);

                    if (matchingTargets.isEmpty()) {
                        LOG.warnf("BSP: no build targets found for module %s", moduleName);
                        return CompletableFuture.completedFuture(new ClasspathInfo(
                                moduleName, moduleDir.toAbsolutePath().toString(),
                                List.of(), List.of(), List.of(), List.of()));
                    }

                    LOG.infof("BSP: found %d targets for module %s", matchingTargets.size(), moduleName);
                    progress.reportProgress("BSP: resolving sources and classpath for " + moduleName);

                    CompletableFuture<List<String>> sourcesFuture =
                            bspServer.getBuildServer()
                                    .buildTargetSources(new SourcesParams(matchingTargets))
                                    .thenApply(result -> extractSourceRoots(result.getItems(), moduleDir));

                    CompletableFuture<List<String>> classpathFuture =
                            bspServer.getJvmBuildServer()
                                    .buildTargetJvmCompileClasspath(new JvmCompileClasspathParams(matchingTargets))
                                    .thenApply(result -> {
                                        List<String> jars = new ArrayList<>();
                                        result.getItems().forEach(item ->
                                                item.getClasspath().forEach(entry -> {
                                                    String path = uriToPath(entry);
                                                    if (path != null && path.endsWith(".jar")) {
                                                        jars.add(path);
                                                    }
                                                }));
                                        return jars;
                                    });

                    return sourcesFuture.thenCombine(classpathFuture, (sourceRoots, classpathJars) -> {
                        LOG.infof("BSP: classpath extracted for %s: %d source roots, %d jars",
                                moduleName, sourceRoots.size(), classpathJars.size());
                        return new ClasspathInfo(
                                moduleName,
                                moduleDir.toAbsolutePath().toString(),
                                sourceRoots,
                                classpathJars,
                                List.of(),
                                List.of());
                    });
                });
    }

    private List<BuildTargetIdentifier> findTargetsForModule(List<BuildTarget> targets, Path moduleDir) {
        Path normalizedModule = moduleDir.toAbsolutePath().normalize();
        List<BuildTargetIdentifier> matching = new ArrayList<>();

        for (BuildTarget target : targets) {
            String baseDir = target.getBaseDirectory();
            if (baseDir == null) {
                continue;
            }
            Path targetPath = uriToPathObj(baseDir);
            if (targetPath != null && targetPath.normalize().equals(normalizedModule)) {
                matching.add(target.getId());
            }
        }

        return matching;
    }

    private List<String> extractSourceRoots(List<ch.epfl.scala.bsp4j.SourcesItem> items, Path moduleDir) {
        List<String> roots = new ArrayList<>();
        for (var item : items) {
            if (item.getRoots() != null) {
                for (String rootUri : item.getRoots()) {
                    Path rootPath = uriToPathObj(rootUri);
                    if (rootPath != null) {
                        String relative = moduleDir.toAbsolutePath().normalize()
                                .relativize(rootPath.normalize())
                                .toString().replace('\\', '/');
                        roots.add(relative);
                    }
                }
            } else {
                for (SourceItem source : item.getSources()) {
                    if (source.getKind() == SourceItemKind.DIRECTORY) {
                        Path srcPath = uriToPathObj(source.getUri());
                        if (srcPath != null) {
                            String relative = moduleDir.toAbsolutePath().normalize()
                                    .relativize(srcPath.normalize())
                                    .toString().replace('\\', '/');
                            roots.add(relative);
                        }
                    }
                }
            }
        }
        return roots;
    }

    private static String uriToPath(String uriOrPath) {
        try {
            URI uri = URI.create(uriOrPath);
            if ("file".equals(uri.getScheme())) {
                return Paths.get(uri).toAbsolutePath().toString();
            }
            return uriOrPath;
        } catch (Exception e) {
            return uriOrPath;
        }
    }

    private static Path uriToPathObj(String uriOrPath) {
        try {
            URI uri = URI.create(uriOrPath);
            if ("file".equals(uri.getScheme())) {
                return Paths.get(uri);
            }
            return Path.of(uriOrPath);
        } catch (Exception e) {
            return null;
        }
    }
}
