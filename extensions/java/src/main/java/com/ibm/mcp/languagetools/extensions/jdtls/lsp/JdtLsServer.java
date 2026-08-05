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
package com.ibm.mcp.languagetools.extensions.jdtls.lsp;

import com.ibm.mcp.languagetools.ContributionManager;
import com.ibm.mcp.languagetools.extensions.jdtls.classpath.FastModeProjectManager;
import com.ibm.mcp.languagetools.extensions.jdtls.classpath.ServerStatusProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.extensions.jdtls.tools.JdtlsCommands;
import com.ibm.mcp.languagetools.installer.InstallerEvent;
import com.ibm.mcp.languagetools.installer.InstallerListener;
import com.ibm.mcp.languagetools.lsp.server.LspServer;
import com.ibm.mcp.languagetools.lsp.server.LspServerConfig;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.logging.Logger;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;

import jakarta.enterprise.inject.spi.CDI;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Custom LSP server for Eclipse JDT.LS.
 * Handles JDT.LS-specific startup logic and readiness detection.
 * Similar to vscode-java's javaServerStarter.ts
 */
public class JdtLsServer extends LspServer implements InstallerListener {

    private static final Logger LOG = Logger.getLogger(JdtLsServer.class);

    public static final String IMPORT_MODE_FAST = "fast";
    public static final String IMPORT_MODE_FAST_CACHE = "fast+cache";
    public static final String IMPORT_MODE_FULL = "full";

    private volatile String importMode;

    public JdtLsServer(LspServerConfig config, Workspace workspace) {
        super(config, workspace);
    }

    /**
     * Returns whether this server is running in fast import mode
     * (M2E/Gradle import disabled, classpath extracted directly from build tool).
     */
    public boolean isFastMode() {
        return IMPORT_MODE_FAST.equals(importMode) || IMPORT_MODE_FAST_CACHE.equals(importMode);
    }

    public boolean isCacheEnabled() {
        return IMPORT_MODE_FAST_CACHE.equals(importMode);
    }

    @Override
    protected CompletableFuture<Void> ensureContributorsInstalled(ProgressMonitor progressMonitor) {
        var application = getWorkspace().getApplication();
        application.addInstallerListener(this);

        ContributionManager.ContributionResult result = application
                .getContributionManager()
                .extractFilesFromContributionWithStatus(getId(), JdtLsContributes.BUNDLES);

        List<ServerConfigBase> contributors = result.getUninstalledContributors();
        if (contributors.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        LOG.infof("Installing %d contributor(s) for JDT.LS", contributors.size());
        var pathManager = application.getPathManager();
        var traceCollector = getConfig().getTraceCollector();
        if (traceCollector != null && traceCollector.isEnabled()) {
            traceCollector.addTrace(getId(),
                    String.format("Installing %d contributor(s)...", contributors.size()),
                    TraceCollector.MessageType.INFO);
        }
        CompletableFuture<?>[] futures = contributors.stream()
                .map(contributor -> {
                    LOG.infof("Installing contributor '%s' bundles for JDT.LS", contributor.getServerId());
                    if (contributor.isContributionOnly() && contributor.getTraceCollector() == null && traceCollector != null) {
                        contributor.setTraceCollector(traceCollector);
                    }
                    return contributor.ensureInstalled(pathManager, null, progressMonitor)
                            .exceptionally(error -> {
                                LOG.warnf(error, "Failed to install contributor '%s' for JDT.LS, continuing without it",
                                        contributor.getServerId());
                                return null;
                            });
                })
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures)
                .thenRun(() -> {
                    LOG.infof("All %d contributor(s) installed for JDT.LS", contributors.size());
                    if (traceCollector != null && traceCollector.isEnabled()) {
                        traceCollector.addTrace(getId(),
                                String.format("All %d contributor(s) installed.", contributors.size()),
                                TraceCollector.MessageType.INFO);
                    }
                });
    }

    /**
     * Prepare initialization options for JDT.LS.
     * Collects bundles from contributes.jdtls and passes them via initializationOptions.bundles.
     */
    @Override
    protected Object prepareInitializationOptions() {
        Map<String, Object> options = new HashMap<>();

        // Start with config-defined initialization options
        var config = super.getConfig();
        if (config.getInitializationOptions() != null && !config.getInitializationOptions().isEmpty()) {
            options.putAll(config.getInitializationOptions());
        }

        // Determine import mode from application settings (configured via admin UI)
        resolveImportMode();

        // Add required JDT.LS settings if not already present
        if (!options.containsKey("settings")) {
            Map<String, Object> settings = new HashMap<>();
            Map<String, Object> javaSettings = new HashMap<>();
            settings.put("java", javaSettings);
            options.put("settings", settings);
        }

        // In fast mode, disable M2E/Gradle import and auto-build
        if (isFastMode()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> settings = (Map<String, Object>) options.get("settings");
            @SuppressWarnings("unchecked")
            Map<String, Object> javaSettings = (Map<String, Object>) settings.get("java");
            if (javaSettings == null) {
                javaSettings = new HashMap<>();
                settings.put("java", javaSettings);
            }
            javaSettings.put("import", Map.of(
                    "maven", Map.of("enabled", false),
                    "gradle", Map.of("enabled", false)
            ));
            // Always disable autobuild in fast mode to prevent JDT.LS from building
            // ALL projects at startup (causes diagnostic flood + hang on large projects
            // like Quarkus). Building is deferred to on-demand diagnostic tools like
            // diagnoseAndFix; search/navigation tools only need the JDT index.
            javaSettings.put("autobuild", Map.of("enabled", false));
            LOG.info("Fast import mode enabled: M2E/Gradle import disabled, autobuild=false");
        }

        // Add extended client capabilities
        if (!options.containsKey("extendedClientCapabilities")) {
            Map<String, Object> extendedCaps = new HashMap<>();
            extendedCaps.put("classFileContentsSupport", true);
            extendedCaps.put("shouldLanguageServerExitOnShutdown", true);
            if (isFastMode()) {
                extendedCaps.put("skipProjectConfiguration", true);
            }
            options.put("extendedClientCapabilities", extendedCaps);
        }

        // Contributors are already installed by ensureContributorsInstalled()
        List<String> bundlePaths = getWorkspace()
                .getApplication()
                .getContributionManager()
                .extractFilesFromContribution(getId(), JdtLsContributes.BUNDLES);

        if (!bundlePaths.isEmpty()) {
            options.put(JdtLsContributes.BUNDLES, bundlePaths);
            LOG.infof("Passing %d bundles to JDT.LS via initializationOptions", bundlePaths.size());
        }

        return options.isEmpty() ? null : options;
    }

    /**
     * Create a JDT.LS-specific language client that handles language/status notifications.
     */
    @Override
    protected LanguageClient createLanguageClient() {
        return new JdtLsLanguageClient(this);
    }

    void onServiceReady() {
        setStatus(ServerStatus.RUNNING);
        setReady(true);
    }

    private static final String DIAGNOSTICS_COMMAND = JdtlsCommands.DIAGNOSTICS;

    /**
     * Get diagnostics for a Java file.
     * Uses the "mcp.jdtls.diagnostics" delegate command handler when the
     * mcp-jdtls bundle is loaded, avoiding the didOpen/publishDiagnostics cycle.
     * Falls back to standard didOpen when the delegate command is not available.
     */
    @Override
    public CompletableFuture<List<Diagnostic>> getDiagnostics(String uri, String languageId, boolean autoClose) {
        if (!hasDiagnosticsCommand()) {
            return super.getDiagnostics(uri, languageId, autoClose);
        }

        List<Diagnostic> cached = getDiagnosticsCache().get(uri);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return ensureModuleSetupIfFastMode(uri)
                .thenCompose(v -> {
                    Object params = Map.of("uris", List.of(uri));
                    return executeCommand(DIAGNOSTICS_COMMAND, List.of(params));
                })
                .thenApply(result -> {
                    List<Diagnostic> diags = parseDiagnosticsResult(result);
                    getDiagnosticsCache().put(uri, diags);
                    return diags;
                })
                .exceptionallyCompose(ex -> {
                    LOG.warnf(ex, "Delegate diagnostics failed for %s, falling back to didOpen", uri);
                    return super.getDiagnostics(uri, languageId, autoClose);
                });
    }

    private CompletableFuture<Void> ensureModuleSetupIfFastMode(String fileUri) {
        if (!isFastMode()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            FastModeProjectManager fmpm = CDI.current().select(FastModeProjectManager.class).get();
            Path workspaceRoot = getWorkspace().getRootPath();
            ServerStatusProgressMonitor progressMonitor = new ServerStatusProgressMonitor(this);
            return fmpm.ensureModuleSetup(workspaceRoot, fileUri, this, progressMonitor)
                    .whenComplete((v, ex) -> progressMonitor.setComplete());
        } catch (Exception e) {
            LOG.debugf(e, "CDI not available for FastModeProjectManager lookup");
            return CompletableFuture.completedFuture(null);
        }
    }

    private boolean hasDiagnosticsCommand() {
        var contributionManager = getWorkspace().getApplication().getContributionManager();
        List<String> bundles = contributionManager.extractFilesFromContribution(getId(), JdtLsContributes.BUNDLES);
        return !bundles.isEmpty();
    }

    private List<Diagnostic> parseDiagnosticsResult(Object result) {
        if (result instanceof List<?> list) {
            return list.stream()
                    .map(item -> {
                        try {
                            var pdp = com.ibm.mcp.languagetools.utils.JsonUtils.toModel(item, PublishDiagnosticsParams.class);
                            return pdp != null ? pdp.getDiagnostics() : null;
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .flatMap(List::stream)
                    .toList();
        }
        return Collections.emptyList();
    }

    @Override
    public void onInstalled(InstallerEvent event) {
        ServerConfigBase config = event.getConfig();
        if (!hasJdtLsBundles(config)) {
            return;
        }
        if (!isReady()) {
            return;
        }
        // Only reload bundles if the contributor is in the same workspace
        if (!getWorkspace().hasLspServer(config.getServerId())) {
            LOG.debugf("Contributor '%s' not in workspace %s, skipping reload",
                    config.getServerId(), getWorkspace().getRootUri());
            return;
        }
        LOG.infof("Contributor '%s' installed in same workspace, reloading bundles", config.getServerId());
        reloadBundles()
                .exceptionally(error -> {
                    LOG.errorf(error, "Failed to reload bundles into JDT.LS");
                    return null;
                });
    }

    private boolean hasJdtLsBundles(ServerConfigBase config) {
        var contributes = config.getContributes();
        if (contributes == null) {
            return false;
        }
        var jdtls = contributes.getContribution(getId());
        if (jdtls == null || !jdtls.isJsonObject()) {
            return false;
        }
        return jdtls.getAsJsonObject().has(JdtLsContributes.BUNDLES);
    }

    private CompletableFuture<Object> reloadBundles() {
        List<String> bundlePaths = getWorkspace()
                .getApplication()
                .getContributionManager()
                .extractFilesFromContribution(getId(), JdtLsContributes.BUNDLES);
        if (bundlePaths.isEmpty()) {
            LOG.warn("No bundles found, skipping java.reloadBundles");
            return CompletableFuture.completedFuture(null);
        }
        LOG.infof("Reloading %d bundles into JDT.LS via java.reloadBundles", bundlePaths.size());
        return executeCommand("java.reloadBundles", List.of(bundlePaths));
    }

    @Override
    public CompletableFuture<String> refreshWorkspace() {
        if (!isReady()) {
            return CompletableFuture.completedFuture("JDT.LS is not ready");
        }
        return executeCommand(JdtlsCommands.REFRESH_PROJECT, List.of())
                .thenApply(result -> {
                    getWorkspace().setNeedsFullBuild(false);
                    return "JDT.LS workspace refreshed: " + result;
                });
    }

    @Override
    public CompletableFuture<String> buildWorkspace(boolean fullBuild) {
        if (!isReady()) {
            return CompletableFuture.completedFuture("JDT.LS is not ready");
        }
        String buildArg = String.format("{\"isFullBuild\":%s}", fullBuild);
        return executeCommand("vscode.java.buildWorkspace", List.of(buildArg))
                .thenApply(result -> {
                    if (fullBuild) {
                        getWorkspace().setNeedsFullBuild(false);
                    }
                    return "JDT.LS build " + (fullBuild ? "full" : "incremental") + ": " + result;
                });
    }

    @Override
    public CompletableFuture<Void> initialize() {
        resolveImportMode();
        return super.initialize()
                .thenRun(() -> {
                    setReady(false);
                    setStatus(ServerStatus.INDEXING);
                    LOG.infof("JDT.LS initialized for workspace: %s (waiting for ServiceReady notification)", workspaceRoot);
                });
    }

    private void resolveImportMode() {
        if (importMode != null) {
            return;
        }
        importMode = IMPORT_MODE_FULL;
        var appConfig = getWorkspace().getApplication().getConfiguration();
        if (appConfig != null) {
            String mode = appConfig.getString("lsp.jdtls.settings.java.import.mode");
            if (IMPORT_MODE_FAST.equals(mode) || IMPORT_MODE_FAST_CACHE.equals(mode)) {
                importMode = mode;
            }
        }
    }

    /**
     * Build the JDT.LS command with custom arguments.
     * Similar to vscode-java's prepareParams (javaServerStarter.ts).
     *
     * Command structure:
     * java [jvm-args] -jar launcher.jar [osgi-args] -configuration [config-dir] -data [workspace]
     */
    @Override
    protected List<String> buildCommand() throws IOException {
        List<String> params = new ArrayList<>();

        // 1. Java executable
        String javaHome = System.getProperty("java.home");
        String javaBin = Paths.get(javaHome, "bin", "java").toString();
        params.add(javaBin);

        // 2. Java module system arguments (required for Java 9+)
        params.add("--add-modules=ALL-SYSTEM");
        params.add("--add-opens");
        params.add("java.base/java.util=ALL-UNNAMED");
        params.add("--add-opens");
        params.add("java.base/java.lang=ALL-UNNAMED");
        params.add("--add-opens");
        params.add("java.base/sun.nio.fs=ALL-UNNAMED");

        // 3. VM arguments from config (e.g., heap size)
        addVMArgs(params);

        // 4. Default arguments if not already present
        addDefaultVMArgsIfMissing(params);

        // 5. Find and add launcher JAR
        addLauncherJar(params);

        // 6. Eclipse/OSGi configuration directory
        params.add("-configuration");
        params.add(getConfigurationDirectory().toString());

        // 7. Eclipse application parameters
        params.add("-Declipse.application=org.eclipse.jdt.ls.core.id1");
        params.add("-Dosgi.bundles.defaultStartLevel=4");
        params.add("-Declipse.product=org.eclipse.jdt.ls.core.product");

        // 8. Workspace data directory
        params.add("-data");
        params.add(getJdtlsDataDir().toString());

        LOG.infof("JDT.LS command: %s", String.join(" ", params));
        return params;
    }

    /**
     * Add VM arguments from workspace configuration (java.jdt.ls.vmargs).
     * Similar to vscode-java's parseVMargs().
     */
    private void addVMArgs(List<String> params) {
        var workspaceConfiguration = getWorkspace().getIdeConfiguration();
        if (workspaceConfiguration == null) {
            LOG.debug("No workspace configuration available, skipping vmargs");
            return;
        }

        String vmargs = workspaceConfiguration.getString("java.jdt.ls.vmargs");
        if (vmargs == null || vmargs.trim().isEmpty()) {
            LOG.debug("No java.jdt.ls.vmargs configured");
            return;
        }

        // Parse vmargs string - handle quoted arguments
        List<String> parsedArgs = parseVMArgsString(vmargs);
        params.addAll(parsedArgs);

        LOG.infof("Added VM args from java.jdt.ls.vmargs: %s", vmargs);
    }

    /**
     * Parse VM arguments string into a list.
     * Handles quotes: "arg with spaces" or -Dfoo="bar baz"
     */
    private List<String> parseVMArgsString(String vmargs) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < vmargs.length(); i++) {
            char c = vmargs.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ' ' && !inQuotes) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            result.add(current.toString());
        }

        return result;
    }

    /**
     * Add default VM arguments if not already present.
     */
    private void addDefaultVMArgsIfMissing(List<String> params) {
        String paramsStr = String.join(" ", params);

        // Disable VM installations detection job
        if (!paramsStr.contains("-DDetectVMInstallationsJob.disabled")) {
            params.add("-DDetectVMInstallationsJob.disabled=true");
        }

        // File encoding (default to UTF-8)
        if (!paramsStr.contains("-Dfile.encoding")) {
            params.add("-Dfile.encoding=UTF-8");
        }

        // Disable JVM logging
        if (!paramsStr.contains("-Xlog")) {
            params.add("-Xlog:disable");
        }

        // Default heap size if not specified
        if (!paramsStr.contains("-Xmx")) {
            params.add("-Xmx1G");
        }
        if (!paramsStr.contains("-Xms")) {
            params.add("-Xms100m");
        }
    }

    /**
     * Find the Eclipse Equinox launcher JAR and add to params.
     */
    private void addLauncherJar(List<String> params) throws IOException {
        Path pluginsDir = getServerHome().resolve("plugins");

        try (var files = Files.walk(pluginsDir, 1)) {
            var launcher = files
                .filter(p -> p.getFileName().toString().startsWith("org.eclipse.equinox.launcher_"))
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .findFirst();

            if (launcher.isPresent()) {
                params.add("-jar");
                params.add(launcher.get().toString());
            } else {
                throw new IOException("Could not find Eclipse Equinox launcher JAR in " + pluginsDir);
            }
        }
    }

    /**
     * Get the configuration directory based on OS.
     * Similar to vscode-java's configDir selection (no syntax server support).
     */
    private Path getConfigurationDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        String configDir;

        if (os.contains("win")) {
            configDir = "config_win";
        } else if (os.contains("mac")) {
            configDir = "config_mac";
        } else {
            configDir = "config_linux";
        }

        return getServerHome().resolve(configDir);
    }

    private Path getJdtlsDataDir() {
        URI rootUri = getWorkspace().getRootUri();
        String workspaceName = Path.of(rootUri).getFileName().toString();
        Path baseDir = getWorkspace().getApplication().getPathManager().getMcpLangToolsRoot().resolve("jdtls-workspaces");
        Path dir = baseDir.resolve(workspaceName + "-" + (rootUri.hashCode() & 0x7FFFFFFF));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create JDT.LS data directory", e);
        }
        return dir;
    }
}
