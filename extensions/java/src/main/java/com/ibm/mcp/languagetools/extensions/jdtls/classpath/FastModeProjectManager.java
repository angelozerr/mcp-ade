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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import com.ibm.mcp.languagetools.extensions.jdtls.classpath.ClasspathInfo.ReactorModule;
import com.ibm.mcp.languagetools.extensions.jdtls.lsp.JdtLsServer;
import com.ibm.mcp.languagetools.extensions.jdtls.tools.JdtlsCommands;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.trace.TraceCollector;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Manages lazy project setup in fast mode.
 *
 * <p>Tracks which modules have been set up with their extracted classpath.
 * On first tool call targeting a module, extracts the classpath from the
 * build tool and creates the JDT project via the {@code mcp.jdtls.setupProject}
 * delegate command. Reactor module dependencies are set up recursively as
 * JDT source projects.</p>
 */
@ApplicationScoped
public class FastModeProjectManager {

    private static final Logger LOG = Logger.getLogger(FastModeProjectManager.class);

    private final ConcurrentHashMap<Path, CompletableFuture<ClasspathInfo>> setupModules =
            new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fast-mode-classpath");
        t.setDaemon(true);
        return t;
    });

    private volatile LspServer currentServer;

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Inject
    ClasspathExtractorRegistry extractorRegistry;

    @Inject
    ClasspathCacheManager cacheManager;

    /**
     * Ensures the module containing the given file is set up in JDT.LS.
     * If already set up, returns immediately. Otherwise, extracts classpath
     * and creates the JDT project. Reactor module dependencies are also
     * set up as source projects.
     *
     * <p>Detects JDT.LS restarts by comparing the server instance reference:
     * when the instance changes, all setup state is cleared so modules are
     * re-configured (using the disk cache if available).</p>
     */
    public CompletableFuture<Void> ensureModuleSetup(Path workspaceRoot, String filePath,
                                                      LspServer jdtls, ProgressMonitor progress) {
        if (currentServer != jdtls) {
            setupModules.clear();
            currentServer = jdtls;
            LOG.info("JDT.LS instance changed, cleared setup modules");
        }

        Path moduleDir = findModuleDir(workspaceRoot, filePath);
        if (moduleDir == null) {
            moduleDir = workspaceRoot;
        }

        return setupModule(workspaceRoot, moduleDir, jdtls, progress);
    }

    private CompletableFuture<Void> setupModule(Path workspaceRoot, Path moduleDir,
                                                  LspServer jdtls, ProgressMonitor progress) {
        Path key = moduleDir.toAbsolutePath().normalize();
        CompletableFuture<ClasspathInfo> existing = setupModules.get(key);
        if (existing != null) {
            return existing.thenApply(v -> null);
        }

        CompletableFuture<ClasspathInfo> setup = new CompletableFuture<>();
        CompletableFuture<ClasspathInfo> prev = setupModules.putIfAbsent(key, setup);
        if (prev != null) {
            return prev.thenApply(v -> null);
        }

        boolean cacheEnabled = jdtls instanceof JdtLsServer j && j.isCacheEnabled();
        final Path finalModuleDir = moduleDir;

        final long setupStartTime = System.currentTimeMillis();
        final AtomicBoolean loadedFromCache = new AtomicBoolean(false);
        return CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().setName("fast-mode-classpath-" + finalModuleDir.getFileName());
            try {
                if (cacheEnabled) {
                    Optional<ClasspathInfo> cached = cacheManager.loadIfValid(workspaceRoot, finalModuleDir);
                    if (cached.isPresent()) {
                        loadedFromCache.set(true);
                        LOG.infof("Using cached classpath for module: %s", cached.get().moduleName());
                        progress.reportProgress("Using cached classpath for " + cached.get().moduleName());
                        traceClasspath(jdtls, cached.get());
                        return cached.get();
                    }
                }

                ClasspathExtractor extractor = extractorRegistry.getExtractor(workspaceRoot);
                if (extractor == null) {
                    throw new ClasspathExtractionException(
                            "No build tool found (Maven or Gradle) in " + workspaceRoot);
                }

                String moduleLabel = workspaceRoot.relativize(finalModuleDir).toString();
                LOG.infof("Extracting classpath for module: %s", moduleLabel);
                progress.reportProgress("Extracting classpath for " + moduleLabel);

                ClasspathInfo info = extractor.extract(workspaceRoot, finalModuleDir, progress);
                LOG.infof("Classpath extracted: %d source roots, %d libraries, %d reactor deps",
                        info.sourceRoots().size(), info.classpathJars().size(),
                        info.reactorModuleDeps().size());

                traceClasspath(jdtls, info);

                if (cacheEnabled) {
                    cacheManager.save(workspaceRoot, finalModuleDir, info);
                }

                return info;
            } catch (ClasspathExtractionException e) {
                throw new RuntimeException(e);
            }
        }, executor).thenCompose(info -> {
            // Skip project creation for reactor POMs with no source code —
            // creating a project at the workspace root would "claim" nested
            // sub-module directories and prevent them from being resolved
            // to their own projects.
            if (info.sourceRoots().isEmpty() && info.classpathJars().isEmpty()
                    && info.reactorModuleDeps().isEmpty()) {
                LOG.infof("Skipping project creation for empty reactor POM: %s", info.moduleName());
                setup.complete(info);
                return CompletableFuture.completedFuture((Void) null);
            }

            if (loadedFromCache.get()) {
                progress.reportProgress("Classpath loaded from cache for " + info.moduleName() + ", setting up project...");
            }

            // Set up reactor module dependencies as source projects
            List<CompletableFuture<Void>> reactorSetups = new ArrayList<>();
            for (ReactorModule reactorDep : info.reactorModuleDeps()) {
                Path reactorDir = Path.of(reactorDep.modulePath());
                reactorSetups.add(setupReactorModule(reactorDir, jdtls, progress));
            }

            return CompletableFuture.allOf(reactorSetups.toArray(new CompletableFuture[0]))
                    .thenCompose(v -> {
                        Map<String, Object> params = new LinkedHashMap<>();
                        params.put("projectName", info.moduleName());
                        params.put("projectPath", info.projectPath());
                        params.put("sourceRoots", info.sourceRoots());
                        params.put("classpathJars", info.classpathJars());
                        List<String> projectRefs = info.reactorModuleDeps().stream()
                                .map(ReactorModule::artifactId)
                                .toList();
                        if (!projectRefs.isEmpty()) {
                            params.put("projectReferences", projectRefs);
                        }

                        LOG.infof("Setting up JDT project: %s", info.moduleName());
                        progress.reportProgress("Setting up project " + info.moduleName());

                        long setupCommandStart = System.currentTimeMillis();
                        return jdtls.executeCommand(JdtlsCommands.SETUP_PROJECT, List.of(params))
                                .thenApply(result -> {
                                    long setupCommandElapsed = System.currentTimeMillis() - setupCommandStart;
                                    LOG.infof("Project setup result: %s (setupProject: %d ms)",
                                            result, setupCommandElapsed);
                                    return (Void) null;
                                })
                                .thenApply(buildResult -> {
                                    long totalElapsed = System.currentTimeMillis() - setupStartTime;
                                    LOG.infof("Module %s ready (total: %d ms)", info.moduleName(), totalElapsed);
                                    progress.reportProgress(String.format(
                                            "Module %s ready (%d ms)", info.moduleName(), totalElapsed));
                                    setup.complete(info);
                                    return (Void) null;
                                });
                    });
        }).exceptionally(ex -> {
            LOG.errorf(ex, "Failed to set up module: %s", moduleDir);
            setup.completeExceptionally(ex);
            setupModules.remove(key);
            return null;
        });
    }

    /**
     * Sets up a reactor module as a minimal source project (no external classpath resolution).
     */
    private CompletableFuture<Void> setupReactorModule(Path moduleDir, LspServer jdtls,
                                                         ProgressMonitor progress) {
        Path key = moduleDir.toAbsolutePath().normalize();
        CompletableFuture<ClasspathInfo> existing = setupModules.get(key);
        if (existing != null) {
            return existing.thenApply(v -> null);
        }

        CompletableFuture<ClasspathInfo> setup = new CompletableFuture<>();
        CompletableFuture<ClasspathInfo> prev = setupModules.putIfAbsent(key, setup);
        if (prev != null) {
            return prev.thenApply(v -> null);
        }

        String moduleName = moduleDir.getFileName().toString();
        List<String> sourceRoots = detectSourceRoots(moduleDir);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("projectName", moduleName);
        params.put("projectPath", moduleDir.toAbsolutePath().toString());
        params.put("sourceRoots", sourceRoots);
        params.put("classpathJars", List.of());
        params.put("disableBuilders", true);

        LOG.infof("Setting up reactor module: %s (source only, no builders)", moduleName);

        return jdtls.executeCommand(JdtlsCommands.SETUP_PROJECT, List.of(params))
                .thenApply(result -> {
                    ClasspathInfo info = new ClasspathInfo(
                            moduleName, moduleDir.toAbsolutePath().toString(),
                            sourceRoots, List.of(), List.of(), List.of());
                    setup.complete(info);
                    return (Void) null;
                })
                .exceptionally(ex -> {
                    LOG.warnf(ex, "Failed to set up reactor module: %s", moduleName);
                    setup.completeExceptionally(ex);
                    setupModules.remove(key);
                    return null;
                });
    }

    /**
     * Checks if the JDT IndexManager has pending indexing jobs.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> isIndexing(LspServer jdtls) {
        return jdtls.executeCommand(JdtlsCommands.GET_INDEXING_STATUS, List.of())
                .thenApply(result -> {
                    if (result instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) result;
                        Object indexing = map.get("indexing");
                        return Boolean.TRUE.equals(indexing);
                    }
                    return false;
                })
                .exceptionally(ex -> {
                    LOG.debugf(ex, "Failed to get indexing status");
                    return false;
                });
    }

    private Path findModuleDir(Path workspaceRoot, String filePath) {
        if (filePath == null) {
            return workspaceRoot;
        }
        Path path;
        try {
            path = Path.of(new URI(filePath));
        } catch (Exception e) {
            path = Path.of(filePath);
        }
        Path current = path.getParent();
        Path rootNorm = workspaceRoot.toAbsolutePath().normalize();

        while (current != null && current.startsWith(rootNorm)) {
            if (Files.exists(current.resolve("pom.xml"))
                    || Files.exists(current.resolve("build.gradle"))
                    || Files.exists(current.resolve("build.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        return workspaceRoot;
    }

    private List<String> detectSourceRoots(Path moduleDir) {
        List<String> roots = new ArrayList<>();
        for (String candidate : List.of("src/main/java", "src/test/java")) {
            if (Files.isDirectory(moduleDir.resolve(candidate))) {
                roots.add(candidate);
            }
        }
        return roots;
    }

    private void traceClasspath(LspServer jdtls, ClasspathInfo info) {
        TraceCollector tc = jdtls.getTraceCollector();
        if (tc == null || !tc.isEnabled()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Classpath for ").append(info.moduleName()).append(":\n");
        for (String jar : info.classpathJars()) {
            sb.append("  ").append(jar).append("\n");
        }
        if (info.reactorModuleDeps() != null && !info.reactorModuleDeps().isEmpty()) {
            sb.append("Reactor modules:\n");
            for (ReactorModule rd : info.reactorModuleDeps()) {
                sb.append("  ").append(rd.artifactId()).append(" -> ").append(rd.modulePath()).append("\n");
            }
        }
        String workspaceUri = jdtls.getWorkspace() != null
                ? jdtls.getWorkspace().getNormalizedUri() : null;
        tc.addTrace(workspaceUri, jdtls.getId(), sb.toString(), TraceCollector.MessageType.INFO);
    }
}
