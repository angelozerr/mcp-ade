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
package com.ibm.mcp.languagetools.extensions.jdtls.build;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.jboss.logging.Logger;

import com.ibm.mcp.languagetools.extensions.jdtls.lsp.JdtLsServer;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.trace.TraceCollector;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Manages lazy project setup with build support.
 *
 * <p>Tracks which modules have been set up with their extracted classpath.
 * On first tool call targeting a module, extracts the classpath from the
 * build tool, writes classpath descriptors, and triggers {@code java.project.import}
 * so that McpProjectImporter creates the JDT projects. Reactor module dependencies
 * are set up as source projects with disabled builders.</p>
 */
@ApplicationScoped
public class BuildSupportManager {

    private static final Logger LOG = Logger.getLogger(BuildSupportManager.class);

    private static final String MCP_CLASSPATH_DIR = "mcp-classpath";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ConcurrentHashMap<Path, CompletableFuture<ClasspathInfo>> setupModules =
            new ConcurrentHashMap<>();

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "build-support-classpath");
        t.setDaemon(true);
        return t;
    });

    private volatile LspServer currentServer;
    private final Set<Path> preloadedParents = ConcurrentHashMap.newKeySet();

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Inject
    BuildSupportRegistry buildSupportRegistry;

    @Inject
    ClasspathCacheManager cacheManager;

    private final List<BspBuildSupport> bspBuildSupports = new ArrayList<>();

    {
        ServiceLoader<BspBuildSupport> loader = ServiceLoader.load(BspBuildSupport.class);
        for (BspBuildSupport support : loader) {
            bspBuildSupports.add(support);
        }
    }

    /**
     * Ensures the module containing the given file is set up in JDT.LS.
     *
     * <p>If the module has already been configured during this session, returns
     * immediately. Otherwise, performs classpath extraction (from cache or build tool),
     * writes classpath descriptors and triggers {@code java.project.import}
     * so McpProjectImporter configures the JDT projects.</p>
     *
     * <p>Detects JDT.LS restarts by comparing the server instance reference:
     * when the instance changes, all setup state is cleared so modules are
     * re-configured (using the disk cache if available).</p>
     *
     * @param workspaceRoot the root directory of the multi-module project
     * @param filePath      the file URI or path that triggered module setup (used to
     *                      determine which module to set up)
     * @param jdtls         the JDT.LS server instance to configure
     * @param progress      progress monitor for reporting extraction and setup progress
     * @return a future that completes when the module and all its reactor dependencies
     *         are set up
     */
    public CompletableFuture<Void> ensureModuleSetup(Path workspaceRoot, String filePath,
                                                      LspServer jdtls, ProgressMonitor progress) {
        if (currentServer != jdtls) {
            setupModules.clear();
            preloadedParents.clear();
            currentServer = jdtls;
            LOG.info("JDT.LS instance changed, cleared setup modules");
        }

        Path moduleDir = findModuleDir(workspaceRoot, filePath);
        if (moduleDir == null) {
            moduleDir = workspaceRoot;
        }

        CompletableFuture<Void> result = setupModule(workspaceRoot, moduleDir, jdtls, progress);

        if (filePath != null) {
            Path rootNorm = workspaceRoot.toAbsolutePath().normalize();
            Path moduleDirNorm = moduleDir.toAbsolutePath().normalize();

            if (moduleDirNorm.equals(rootNorm)) {
                // Root module: preload child modules of the workspace root
                if (preloadedParents.add(rootNorm)) {
                    final Path wsRoot = workspaceRoot;
                    result.thenRunAsync(() -> preloadSiblingModules(wsRoot, wsRoot, jdtls), executor);
                }
            } else {
                // Submodule: preload sibling modules under the same parent
                Path parentDir = moduleDir.getParent();
                if (parentDir != null && parentDir.toAbsolutePath().normalize().startsWith(rootNorm)
                        && preloadedParents.add(parentDir.toAbsolutePath().normalize())) {
                    final Path wsRoot = workspaceRoot;
                    final Path finalParentDir = parentDir;
                    result.thenRunAsync(() -> preloadSiblingModules(wsRoot, finalParentDir, jdtls), executor);
                }
            }
        }

        return result;
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

        boolean cacheEnabled = jdtls instanceof JdtLsServer j && j.isFastMode();
        boolean bspMode = jdtls instanceof JdtLsServer j2 && j2.isGradleBspMode();
        final Path finalModuleDir = moduleDir;

        final long setupStartTime = System.currentTimeMillis();
        final AtomicBoolean loadedFromCache = new AtomicBoolean(false);

        CompletableFuture<ClasspathInfo> extractionFuture = extractClasspath(
                workspaceRoot, finalModuleDir, jdtls, progress,
                cacheEnabled, bspMode, loadedFromCache);

        return extractionFuture.thenCompose(info -> {
            if (info.sourceRoots().isEmpty() && info.classpathJars().isEmpty()
                    && info.reactorModuleDeps().isEmpty()) {
                LOG.infof("Skipping project creation for empty module: %s", info.moduleName());
                setup.complete(info);
                return CompletableFuture.completedFuture((Void) null);
            }

            if (loadedFromCache.get()) {
                progress.reportProgress("Classpath loaded from cache for " + info.moduleName() + ", setting up project...");
            }

            List<String> projectRefs = info.reactorModuleDeps().stream()
                    .map(ClasspathInfo.ReactorModule::artifactId)
                    .toList();

            boolean descriptorExisted = hasClasspathDescriptor(jdtls, info.moduleName());

            if (loadedFromCache.get() && descriptorExisted) {
                // Cached fast path: McpProjectImporter already handled during initialize
                writeClasspathDescriptor(jdtls, info, projectRefs);
                registerReactorModules(info);
                long totalElapsed = System.currentTimeMillis() - setupStartTime;
                String readyMsg = String.format(
                        "Module %s ready (cached, %d ms)", info.moduleName(), totalElapsed);
                LOG.infof("Skipping import for %s (classpath unchanged, project exists)",
                        info.moduleName());
                progress.reportProgress(readyMsg);
                setup.complete(info);
                return CompletableFuture.completedFuture((Void) null);
            }

            // Write reactor module descriptors (with disableBuilders=true)
            for (ClasspathInfo.ReactorModule reactorDep : info.reactorModuleDeps()) {
                List<String> sourceRoots = detectSourceRoots(Path.of(reactorDep.modulePath()));
                ClasspathInfo reactorInfo = new ClasspathInfo(
                        reactorDep.artifactId(), reactorDep.modulePath(),
                        sourceRoots, List.of(), List.of(), List.of());
                writeClasspathDescriptor(jdtls, reactorInfo, List.of(), true);
            }
            registerReactorModules(info);

            // Write main module descriptor
            writeClasspathDescriptor(jdtls, info, projectRefs);

            // Trigger McpProjectImporter via java.project.import
            LOG.infof("Importing project: %s", info.moduleName());
            progress.reportProgress("Setting up project " + info.moduleName());

            long importStart = System.currentTimeMillis();
            return jdtls.executeCommand("java.project.import", List.of())
                    .thenApply(result -> {
                        long importElapsed = System.currentTimeMillis() - importStart;
                        LOG.infof("Project import completed (import: %d ms)", importElapsed);
                        return (Void) null;
                    })
                    .thenApply(v -> {
                        long totalElapsed = System.currentTimeMillis() - setupStartTime;
                        String readyMsg = String.format(
                                "Module %s ready (%d ms)", info.moduleName(), totalElapsed);
                        LOG.infof(readyMsg);
                        progress.reportProgress(readyMsg);
                        setup.complete(info);
                        return (Void) null;
                    });
        }).exceptionally(ex -> {
            String failMsg = String.format("Failed to set up module: %s (%s)",
                    moduleDir.getFileName(), ex.getMessage());
            LOG.error(failMsg, ex);
            traceInfo(jdtls, failMsg);
            setup.completeExceptionally(ex);
            setupModules.remove(key);
            return null;
        });
    }

    private CompletableFuture<ClasspathInfo> extractClasspath(Path workspaceRoot, Path moduleDir,
                                                               LspServer jdtls, ProgressMonitor progress,
                                                               boolean cacheEnabled, boolean bspMode,
                                                               AtomicBoolean loadedFromCache) {
        return CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().setName("build-support-classpath-" + moduleDir.getFileName());
            if (cacheEnabled) {
                Optional<ClasspathInfo> cached = cacheManager.loadIfValid(workspaceRoot, moduleDir);
                if (cached.isPresent()) {
                    loadedFromCache.set(true);
                    LOG.infof("Using cached classpath for module: %s", cached.get().moduleName());
                    progress.reportProgress("Using cached classpath for " + cached.get().moduleName());
                    traceClasspath(jdtls, cached.get());
                    return cached.get();
                }
            }
            return (ClasspathInfo) null;
        }, executor).thenCompose(cachedInfo -> {
            if (cachedInfo != null) {
                return CompletableFuture.completedFuture(cachedInfo);
            }

            if (bspMode) {
                BspBuildSupport bspSupport = bspBuildSupports.isEmpty() ? null : bspBuildSupports.get(0);
                if (bspSupport != null) {
                    return jdtls.getWorkspace()
                            .ensureBspServerReady(bspSupport.getBspServerId(), progress)
                            .thenCompose(bspServer -> bspSupport.extractAsync(
                                    bspServer, workspaceRoot, moduleDir, progress))
                            .thenApply(info -> {
                                traceClasspath(jdtls, info);
                                if (cacheEnabled) {
                                    cacheManager.save(workspaceRoot, moduleDir, info);
                                }
                                return info;
                            });
                }
            }

            return CompletableFuture.supplyAsync(() -> {
                try {
                    BuildSupport buildSupport = buildSupportRegistry.getBuildSupport(workspaceRoot);
                    if (buildSupport == null) {
                        throw new BuildSupportException(
                                "No build tool found (Maven or Gradle) in " + workspaceRoot);
                    }

                    String moduleLabel = workspaceRoot.relativize(moduleDir).toString();
                    LOG.infof("Extracting classpath for module: %s", moduleLabel);
                    progress.reportProgress("Extracting classpath for " + moduleLabel);

                    ClasspathInfo info = buildSupport.extract(workspaceRoot, moduleDir, progress);
                    LOG.infof("Classpath extracted: %d source roots, %d libraries, %d reactor deps",
                            info.sourceRoots().size(), info.classpathJars().size(),
                            info.reactorModuleDeps().size());

                    traceClasspath(jdtls, info);

                    if (cacheEnabled) {
                        cacheManager.save(workspaceRoot, moduleDir, info);
                    }

                    return info;
                } catch (BuildSupportException e) {
                    throw new RuntimeException(e);
                }
            }, executor);
        });
    }

    private void registerReactorModules(ClasspathInfo info) {
        for (ClasspathInfo.ReactorModule reactorDep : info.reactorModuleDeps()) {
            Path key = Path.of(reactorDep.modulePath()).toAbsolutePath().normalize();
            List<String> sourceRoots = detectSourceRoots(Path.of(reactorDep.modulePath()));
            ClasspathInfo reactorInfo = new ClasspathInfo(
                    reactorDep.artifactId(), reactorDep.modulePath(),
                    sourceRoots, List.of(), List.of(), List.of());
            CompletableFuture<ClasspathInfo> reactorSetup = new CompletableFuture<>();
            if (setupModules.putIfAbsent(key, reactorSetup) == null) {
                reactorSetup.complete(reactorInfo);
            }
        }
    }

    /**
     * Checks whether the JDT IndexManager is currently indexing.
     *
     * <p>Queries the {@code mcp.jdtls.getIndexingStatus} command and returns
     * {@code true} if the server reports active indexing jobs. Returns {@code false}
     * on error (e.g., if the command is not supported).</p>
     *
     * @param jdtls the JDT.LS server to query
     * @return a future that resolves to {@code true} if indexing is in progress
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> isIndexing(LspServer jdtls) {
        return jdtls.executeCommand("mcp.jdtls.getIndexingStatus", List.of())
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
            if (buildSupportRegistry.hasBuildFile(current)) {
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

    private void preloadSiblingModules(Path workspaceRoot, Path parentDir, LspServer jdtls) {
        List<Path> siblings = buildSupportRegistry.discoverSiblingModules(parentDir);
        int total = siblings.size();
        String parentName = parentDir.getFileName().toString();
        LOG.infof("Sibling pre-loading: %d modules discovered under %s", total, parentName);
        traceInfo(jdtls, String.format("Sibling pre-loading: %d modules under %s", total, parentName));

        if (total == 0) {
            return;
        }

        AtomicBoolean anyFailed = new AtomicBoolean(false);
        long startTime = System.currentTimeMillis();
        AtomicInteger loaded = new AtomicInteger(0);

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Path moduleDir : siblings) {
            chain = chain.thenCompose(v ->
                    setupModule(workspaceRoot, moduleDir, jdtls, ProgressMonitor.none())
                            .thenRun(() -> {
                                int count = loaded.incrementAndGet();
                                Path mKey = moduleDir.toAbsolutePath().normalize();
                                if (setupModules.containsKey(mKey)) {
                                    String msg = String.format("Sibling pre-loading: %d/%d (%s)",
                                            count, total, moduleDir.getFileName());
                                    LOG.infof(msg);
                                    traceInfo(jdtls, msg);
                                } else {
                                    anyFailed.set(true);
                                    String msg = String.format("Sibling pre-load failed: %d/%d (%s)",
                                            count, total, moduleDir.getFileName());
                                    LOG.warnf(msg);
                                    traceInfo(jdtls, msg);
                                }
                            }));
        }

        chain.thenRun(() -> {
            long elapsed = System.currentTimeMillis() - startTime;
            String msg = String.format("Sibling pre-loading complete (%s): %d modules in %d ms%s",
                    parentName, total, elapsed, anyFailed.get() ? " (some failures)" : "");
            LOG.infof(msg);
            traceInfo(jdtls, msg);
        });
    }


    /**
     * Writes classpath descriptors from the cache for all cached modules
     * and their reactor module dependencies.
     * Called before JDTLS initialize so that McpProjectImporter and McpBuildSupport
     * can set up projects during initialization.
     *
     * @return the number of descriptors written
     */
    public int writeDescriptorsFromCache(Path workspaceRoot, LspServer jdtls) {
        List<ClasspathInfo> cachedModules = cacheManager.loadAllValid(workspaceRoot);
        int count = 0;
        Set<String> written = new HashSet<>();

        for (ClasspathInfo info : cachedModules) {
            List<String> projectRefs = info.reactorModuleDeps() != null
                    ? info.reactorModuleDeps().stream()
                    .map(ClasspathInfo.ReactorModule::artifactId)
                    .toList()
                    : List.of();
            writeClasspathDescriptor(jdtls, info, projectRefs);
            written.add(info.moduleName());
            count++;

            // Write descriptors for reactor module dependencies (source-only projects)
            if (info.reactorModuleDeps() != null) {
                for (ClasspathInfo.ReactorModule reactorDep : info.reactorModuleDeps()) {
                    if (written.contains(reactorDep.artifactId())) {
                        continue;
                    }
                    List<String> sourceRoots = detectSourceRoots(Path.of(reactorDep.modulePath()));
                    ClasspathInfo reactorInfo = new ClasspathInfo(
                            reactorDep.artifactId(), reactorDep.modulePath(),
                            sourceRoots, List.of(), List.of(), List.of());
                    writeClasspathDescriptor(jdtls, reactorInfo, List.of(), true);
                    written.add(reactorDep.artifactId());
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Writes a classpath descriptor JSON file to the JDTLS data dir for the
     * McpBuildSupport to read during the JDTLS lifecycle.
     */
    private void writeClasspathDescriptor(LspServer jdtls, ClasspathInfo info, List<String> projectRefs) {
        writeClasspathDescriptor(jdtls, info, projectRefs, false);
    }

    private void writeClasspathDescriptor(LspServer jdtls, ClasspathInfo info, List<String> projectRefs,
                                          boolean disableBuilders) {
        Path cpFile = getDescriptorFile(jdtls, info.moduleName());
        if (cpFile == null) {
            return;
        }
        Path cpDir = cpFile.getParent();
        try {
            Files.createDirectories(cpDir);
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("projectName", info.moduleName());
            descriptor.put("projectPath", info.projectPath());
            descriptor.put("sourceRoots", info.sourceRoots());
            descriptor.put("classpathJars", info.classpathJars());
            if (projectRefs != null && !projectRefs.isEmpty()) {
                descriptor.put("projectReferences", projectRefs);
            }
            if (disableBuilders) {
                descriptor.put("disableBuilders", true);
            }
            try (Writer writer = Files.newBufferedWriter(cpFile)) {
                GSON.toJson(descriptor, writer);
            }
            LOG.debugf("Wrote classpath descriptor: %s", cpFile);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to write classpath descriptor: %s", cpFile);
        }
    }

    private Path getDescriptorFile(LspServer jdtls, String projectName) {
        if (!(jdtls instanceof JdtLsServer j)) {
            return null;
        }
        return j.getJdtlsDataDir().resolve(MCP_CLASSPATH_DIR).resolve(projectName + ".json");
    }

    private boolean hasClasspathDescriptor(LspServer jdtls, String projectName) {
        Path cpFile = getDescriptorFile(jdtls, projectName);
        return cpFile != null && Files.exists(cpFile);
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
            for (ClasspathInfo.ReactorModule rd : info.reactorModuleDeps()) {
                sb.append("  ").append(rd.artifactId()).append(" -> ").append(rd.modulePath()).append("\n");
            }
        }
        String workspaceUri = jdtls.getWorkspace() != null
                ? jdtls.getWorkspace().getNormalizedUri() : null;
        tc.addTrace(workspaceUri, jdtls.getId(), sb.toString(), TraceCollector.MessageType.INFO);
    }

    private void traceInfo(LspServer jdtls, String message) {
        TraceCollector tc = jdtls.getTraceCollector();
        if (tc == null || !tc.isEnabled()) {
            return;
        }
        String workspaceUri = jdtls.getWorkspace() != null
                ? jdtls.getWorkspace().getNormalizedUri() : null;
        tc.addTrace(workspaceUri, jdtls.getId(), message, TraceCollector.MessageType.INFO);
    }
}
