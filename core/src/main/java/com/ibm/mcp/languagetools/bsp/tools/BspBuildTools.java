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
package com.ibm.mcp.languagetools.bsp.tools;

import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.bsp.server.BspServer;
import com.ibm.mcp.languagetools.bsp.server.BspServerConfig;
import com.ibm.mcp.languagetools.extension.ExtensionRegistry;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerStatus;
import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
import com.ibm.mcp.languagetools.tools.ToolException;
import com.ibm.mcp.languagetools.workspace.Workspace;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for BSP (Build Server Protocol) operations.
 * <p>
 * Provides tools to interact with BSP build servers:
 * - List build servers and their status
 * - Get build targets (modules, subprojects)
 * - Compile, test, clean build targets
 * - Query sources, dependencies, resources
 */
@ApplicationScoped
public class BspBuildTools {

    private static final Logger LOG = Logger.getLogger(BspBuildTools.class);

    @Inject
    Application application;

    // ========== Server Management ==========

    @Tool(
            name = "list_build_servers",
            description = "Get information about configured BSP build servers (ID, name, description). " +
                    "Without cwd: returns available server configurations. " +
                    "With cwd: returns server configurations enriched with runtime state " +
                    "(status, ready, statusMessage).")
    public List<Map<String, Object>> listBuildServers(
            @ToolArg(description = ToolArgDescriptions.CWD, required = false) String cwd) {
        try {
            Workspace workspace = (cwd != null && !cwd.isEmpty())
                    ? application.getWorkspaceForPath(cwd)
                    : null;

            var configs = application.getBspServerConfigs();
            List<Map<String, Object>> result = new ArrayList<>();

            for (var config : configs) {
                Map<String, Object> server = new LinkedHashMap<>();
                server.put("id", config.getServerId());
                server.put("name", config.getName());
                server.put("description", config.getDescription() != null ? config.getDescription() : "");
                if (config.getUrl() != null) {
                    server.put("url", config.getUrl());
                }

                ExtensionRegistry extRegistry = application.getExtensionRegistry();
                String extensionId = config.getExtensionId();
                if (extensionId != null) {
                    server.put("extensionId", extensionId);
                }
                boolean enabled = extRegistry.isExtensionEnabled(extensionId != null ? extensionId : config.getServerId())
                        && extRegistry.isServerEnabled(config.getServerId());
                server.put("enabled", enabled);

                if (workspace != null) {
                    BspServer bspServer = workspace.getBspServer(config.getServerId());
                    if (bspServer != null) {
                        server.put("status", bspServer.getStatus().name());
                        server.put("ready", bspServer.isReady());
                        String statusMessage = bspServer.getStatusMessage();
                        if (statusMessage != null) {
                            server.put("statusMessage", statusMessage);
                        }
                        String errorMessage = bspServer.getErrorMessage();
                        if (errorMessage != null) {
                            server.put("error", errorMessage);
                        }
                    } else {
                        server.put("status", ServerStatus.NOT_STARTED.name());
                        server.put("ready", false);
                    }
                    config.addInstallationStatus(server);
                }

                result.add(server);
            }

            return result;
        } catch (Exception e) {
            LOG.error("Failed to list build servers", e);
            throw new ToolException("Failed to list build servers: " + e.getMessage(), e);
        }
    }

    // ========== Build Targets ==========

    @Tool(
            name = "get_build_targets",
            description = "Get all build targets in the workspace. " +
                    "A build target represents a unit of compilation " +
                    "(e.g., a Maven module, Gradle subproject, sbt project).")
    public CompletableFuture<String> getBuildTargets(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd) {
        return ensureBspServerReady(cwd)
                .thenCompose(bspServer -> bspServer.getBuildServer().workspaceBuildTargets())
                .thenApply(this::formatBuildTargets);
    }

    // ========== Compile ==========

    @Tool(
            name = "compile_build_target",
            description = "Compile one or more build targets. " +
                    "Returns compilation status and any diagnostics.")
    public CompletableFuture<String> compileBuildTarget(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "List of build target URIs to compile") List<String> targetIds) {
        return ensureBspServerReady(cwd)
                .thenCompose(bspServer -> {
                    List<ch.epfl.scala.bsp4j.BuildTargetIdentifier> targets = targetIds.stream()
                            .map(ch.epfl.scala.bsp4j.BuildTargetIdentifier::new)
                            .toList();
                    ch.epfl.scala.bsp4j.CompileParams params = new ch.epfl.scala.bsp4j.CompileParams(targets);
                    return bspServer.getBuildServer().buildTargetCompile(params);
                })
                .thenApply(this::formatCompileResult);
    }

    // ========== Sources ==========

    @Tool(
            name = "get_build_target_sources",
            description = "Get the source directories and files for one or more build targets.")
    public CompletableFuture<String> getBuildTargetSources(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "List of build target URIs") List<String> targetIds) {
        return ensureBspServerReady(cwd)
                .thenCompose(bspServer -> {
                    List<ch.epfl.scala.bsp4j.BuildTargetIdentifier> targets = targetIds.stream()
                            .map(ch.epfl.scala.bsp4j.BuildTargetIdentifier::new)
                            .toList();
                    ch.epfl.scala.bsp4j.SourcesParams params = new ch.epfl.scala.bsp4j.SourcesParams(targets);
                    return bspServer.getBuildServer().buildTargetSources(params);
                })
                .thenApply(this::formatSourcesResult);
    }

    // ========== Dependencies ==========

    @Tool(
            name = "get_build_target_dependencies",
            description = "Get the dependency sources (external JARs with sources) for one or more build targets.")
    public CompletableFuture<String> getBuildTargetDependencies(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "List of build target URIs") List<String> targetIds) {
        return ensureBspServerReady(cwd)
                .thenCompose(bspServer -> {
                    List<ch.epfl.scala.bsp4j.BuildTargetIdentifier> targets = targetIds.stream()
                            .map(ch.epfl.scala.bsp4j.BuildTargetIdentifier::new)
                            .toList();
                    ch.epfl.scala.bsp4j.DependencySourcesParams params =
                            new ch.epfl.scala.bsp4j.DependencySourcesParams(targets);
                    return bspServer.getBuildServer().buildTargetDependencySources(params);
                })
                .thenApply(this::formatDependencySourcesResult);
    }

    // ========== Resources ==========

    @Tool(
            name = "get_build_target_resources",
            description = "Get the resource directories for one or more build targets.")
    public CompletableFuture<String> getBuildTargetResources(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "List of build target URIs") List<String> targetIds) {
        return ensureBspServerReady(cwd)
                .thenCompose(bspServer -> {
                    List<ch.epfl.scala.bsp4j.BuildTargetIdentifier> targets = targetIds.stream()
                            .map(ch.epfl.scala.bsp4j.BuildTargetIdentifier::new)
                            .toList();
                    ch.epfl.scala.bsp4j.ResourcesParams params = new ch.epfl.scala.bsp4j.ResourcesParams(targets);
                    return bspServer.getBuildServer().buildTargetResources(params);
                })
                .thenApply(this::formatResourcesResult);
    }

    // ========== Test ==========

    @Tool(
            name = "test_build_target",
            description = "Run tests for one or more build targets. Returns test results.")
    public CompletableFuture<String> testBuildTarget(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "List of build target URIs to test") List<String> targetIds) {
        return ensureBspServerReady(cwd)
                .thenCompose(bspServer -> {
                    List<ch.epfl.scala.bsp4j.BuildTargetIdentifier> targets = targetIds.stream()
                            .map(ch.epfl.scala.bsp4j.BuildTargetIdentifier::new)
                            .toList();
                    ch.epfl.scala.bsp4j.TestParams params = new ch.epfl.scala.bsp4j.TestParams(targets);
                    return bspServer.getBuildServer().buildTargetTest(params);
                })
                .thenApply(this::formatTestResult);
    }

    // ========== Clean ==========

    @Tool(
            name = "clean_build_target",
            description = "Clean the build outputs for one or more build targets.")
    public CompletableFuture<String> cleanBuildTarget(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "List of build target URIs to clean") List<String> targetIds) {
        return ensureBspServerReady(cwd)
                .thenCompose(bspServer -> {
                    List<ch.epfl.scala.bsp4j.BuildTargetIdentifier> targets = targetIds.stream()
                            .map(ch.epfl.scala.bsp4j.BuildTargetIdentifier::new)
                            .toList();
                    ch.epfl.scala.bsp4j.CleanCacheParams params = new ch.epfl.scala.bsp4j.CleanCacheParams(targets);
                    return bspServer.getBuildServer().buildTargetCleanCache(params);
                })
                .thenApply(this::formatCleanCacheResult);
    }

    // ========== Internal helpers ==========

    /**
     * Find and ensure a BSP server is ready for the given workspace path.
     * Returns a CompletableFuture that completes with the ready BSP server.
     *
     * @param cwd the workspace root path
     * @return a future completing with the BSP server instance
     */
    private CompletableFuture<BspServer> ensureBspServerReady(String cwd) {
        Workspace workspace = application.getWorkspaceForPath(cwd);
        if (workspace == null) {
            return CompletableFuture.failedFuture(new ToolException("No workspace found for: " + cwd));
        }

        // Find first matching enabled BSP server config
        for (BspServerConfig config : application.getBspServerConfigs()) {
            ExtensionRegistry extRegistry = application.getExtensionRegistry();
            String extensionId = config.getExtensionId();
            boolean enabled = extRegistry.isExtensionEnabled(extensionId != null ? extensionId : config.getServerId())
                    && extRegistry.isServerEnabled(config.getServerId());
            if (!enabled) {
                continue;
            }

            // Check if the server can handle this workspace
            if (config.canHandle(workspace.getRootUri().toASCIIString(), workspace.getRootPath())) {
                return workspace.ensureBspServerReady(config.getServerId(), ProgressMonitor.none());
            }
        }

        return CompletableFuture.failedFuture(new ToolException("No BSP build server available for workspace: " + cwd));
    }

    // ========== Formatters ==========

    private String formatBuildTargets(ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult targetsResult) {
        StringBuilder sb = new StringBuilder("Build Targets:\n");
        for (ch.epfl.scala.bsp4j.BuildTarget target : targetsResult.getTargets()) {
            sb.append("- ").append(target.getId().getUri());
            if (target.getDisplayName() != null) {
                sb.append(" (").append(target.getDisplayName()).append(")");
            }
            sb.append("\n");
            if (target.getLanguageIds() != null && !target.getLanguageIds().isEmpty()) {
                sb.append("  Languages: ").append(String.join(", ", target.getLanguageIds())).append("\n");
            }
            if (target.getCapabilities() != null) {
                sb.append("  Capabilities: ");
                List<String> caps = new ArrayList<>();
                if (target.getCapabilities().getCanCompile()) caps.add("compile");
                if (target.getCapabilities().getCanTest()) caps.add("test");
                if (target.getCapabilities().getCanRun()) caps.add("run");
                if (target.getCapabilities().getCanDebug()) caps.add("debug");
                sb.append(String.join(", ", caps)).append("\n");
            }
        }
        return sb.toString();
    }

    private String formatCompileResult(ch.epfl.scala.bsp4j.CompileResult result) {
        StringBuilder sb = new StringBuilder("Compile Result:\n");
        sb.append("Status: ").append(result.getStatusCode()).append("\n");
        if (result.getOriginId() != null) {
            sb.append("Origin ID: ").append(result.getOriginId()).append("\n");
        }
        return sb.toString();
    }

    private String formatSourcesResult(ch.epfl.scala.bsp4j.SourcesResult result) {
        StringBuilder sb = new StringBuilder("Sources:\n");
        for (ch.epfl.scala.bsp4j.SourcesItem item : result.getItems()) {
            sb.append("Target: ").append(item.getTarget().getUri()).append("\n");
            for (ch.epfl.scala.bsp4j.SourceItem source : item.getSources()) {
                sb.append("  - ").append(source.getUri());
                sb.append(" (").append(source.getGenerated() ? "generated" : "source").append(")\n");
            }
        }
        return sb.toString();
    }

    private String formatDependencySourcesResult(ch.epfl.scala.bsp4j.DependencySourcesResult result) {
        StringBuilder sb = new StringBuilder("Dependency Sources:\n");
        for (ch.epfl.scala.bsp4j.DependencySourcesItem item : result.getItems()) {
            sb.append("Target: ").append(item.getTarget().getUri()).append("\n");
            for (String source : item.getSources()) {
                sb.append("  - ").append(source).append("\n");
            }
        }
        return sb.toString();
    }

    private String formatResourcesResult(ch.epfl.scala.bsp4j.ResourcesResult result) {
        StringBuilder sb = new StringBuilder("Resources:\n");
        for (ch.epfl.scala.bsp4j.ResourcesItem item : result.getItems()) {
            sb.append("Target: ").append(item.getTarget().getUri()).append("\n");
            for (String resource : item.getResources()) {
                sb.append("  - ").append(resource).append("\n");
            }
        }
        return sb.toString();
    }

    private String formatTestResult(ch.epfl.scala.bsp4j.TestResult result) {
        StringBuilder sb = new StringBuilder("Test Result:\n");
        sb.append("Status: ").append(result.getStatusCode()).append("\n");
        if (result.getOriginId() != null) {
            sb.append("Origin ID: ").append(result.getOriginId()).append("\n");
        }
        return sb.toString();
    }

    private String formatCleanCacheResult(ch.epfl.scala.bsp4j.CleanCacheResult result) {
        StringBuilder sb = new StringBuilder("Clean Result:\n");
        sb.append("Cleaned: ").append(result.getCleaned()).append("\n");
        if (result.getMessage() != null) {
            sb.append("Message: ").append(result.getMessage()).append("\n");
        }
        return sb.toString();
    }
}
