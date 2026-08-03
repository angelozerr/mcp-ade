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
package com.ibm.mcp.languagetools.extensions.jdtls.tools;

import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.extensions.jdtls.classpath.FastModeProjectManager;
import com.ibm.mcp.languagetools.extensions.jdtls.classpath.ServerStatusProgressMonitor;
import com.ibm.mcp.languagetools.extensions.jdtls.lsp.JdtLsServer;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Executor for JDT.LS delegate commands.
 * Resolves the JDT.LS server for a workspace and sends executeCommand requests.
 *
 * <p>In fast import mode, lazily sets up the JDT project for the target module
 * before executing the command, and enriches results with mode/indexing metadata.</p>
 */
@ApplicationScoped
public class JdtlsCommandExecutor {

    private static final Logger LOG = Logger.getLogger(JdtlsCommandExecutor.class);

    private static final String JDTLS_SERVER_ID = "jdtls";

    private static final com.google.gson.Gson GSON =
            new com.google.gson.GsonBuilder().setPrettyPrinting().create();

    @Inject
    Application application;

    @Inject
    FastModeProjectManager fastModeProjectManager;

    @SuppressWarnings("unchecked")
    public CompletableFuture<String> executeCommand(String cwd, String commandId, Object arguments,
                                                     Cancellation cancellation, Progress progress) {
        return executeCommandWithMetadata(cwd, commandId, arguments, null)
                .thenApply(this::formatResult)
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to execute command %s", commandId);
                    return "Error executing " + commandId + ": " + ex.getMessage();
                });
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Object> executeCommandRaw(String cwd, String commandId, Object arguments) {
        var workspace = application.getWorkspaceForPath(cwd);

        return workspace.ensureLspServerReady(JDTLS_SERVER_ID, ProgressMonitor.none())
                .thenCompose(jdtls -> {
                    List<Object> args = arguments instanceof List
                            ? (List<Object>) arguments
                            : List.of(arguments);
                    return jdtls.executeCommand(commandId, args);
                });
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> executeCommandWithMetadata(String cwd, String commandId,
                                                                   Object arguments, String fileUri) {
        var workspace = application.getWorkspaceForPath(cwd);

        return workspace.ensureLspServerReady(JDTLS_SERVER_ID, ProgressMonitor.none())
                .thenCompose(jdtls -> {
                    boolean fastMode = jdtls instanceof JdtLsServer j && j.isFastMode();
                    ServerStatusProgressMonitor progressMonitor = fastMode
                            ? new ServerStatusProgressMonitor(jdtls) : null;

                    CompletableFuture<Void> setupFuture;
                    if (fastMode) {
                        String targetFile = extractFileUri(arguments, fileUri);
                        Path workspaceRoot = workspace.getRootPath();
                        setupFuture = fastModeProjectManager.ensureModuleSetup(
                                workspaceRoot, targetFile, jdtls, progressMonitor);
                    } else {
                        setupFuture = CompletableFuture.completedFuture(null);
                    }

                    return setupFuture
                            .whenComplete((v, ex) -> {
                                if (progressMonitor != null) {
                                    progressMonitor.setComplete();
                                }
                            })
                            .thenCompose(v -> {
                                List<Object> args = arguments instanceof List
                                        ? (List<Object>) arguments
                                        : List.of(arguments);
                                CompletableFuture<Object> commandFuture =
                                        jdtls.executeCommand(commandId, args);
                                if (!fastMode) {
                                    return commandFuture;
                                }
                                CompletableFuture<Boolean> indexingFuture =
                                        fastModeProjectManager.isIndexing(jdtls);
                                return commandFuture.thenCombine(indexingFuture,
                                        this::enrichResultWithMetadata);
                            });
                });
    }

    @SuppressWarnings("unchecked")
    private String extractFileUri(Object arguments, String fileUri) {
        if (fileUri != null) {
            return fileUri;
        }
        Map<String, Object> params = null;
        if (arguments instanceof Map) {
            params = (Map<String, Object>) arguments;
        } else if (arguments instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
            params = (Map<String, Object>) list.get(0);
        }
        if (params != null) {
            Object uri = params.get("uri");
            if (uri == null) {
                uri = params.get("fileUri");
            }
            if (uri instanceof String s) {
                return s;
            }
        }
        return null;
    }

    private Object enrichResultWithMetadata(Object result, boolean indexing) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        enriched.put("importMode", "fast");
        if (indexing) {
            enriched.put("indexingStatus", "in_progress");
            enriched.put("indexingNote",
                    "Indexing is still in progress. Search-based results (find references, "
                            + "type hierarchy, rename across files) may be incomplete.");
        } else {
            enriched.put("indexingStatus", "complete");
        }
        enriched.put("result", result);
        return enriched;
    }

    public CompletableFuture<String> executeBatchCommand(String cwd, String commandId,
                                                          List<String> fileUris,
                                                          Function<String, Object> argsBuilder,
                                                          Cancellation cancellation, Progress progress) {
        var workspace = application.getWorkspaceForPath(cwd);

        return workspace.ensureLspServerReady(JDTLS_SERVER_ID, ProgressMonitor.none())
                .thenCompose(jdtls -> {
                    boolean fastMode = jdtls instanceof JdtLsServer j && j.isFastMode();
                    ServerStatusProgressMonitor progressMonitor = fastMode
                            ? new ServerStatusProgressMonitor(jdtls) : null;

                    CompletableFuture<Void> setupFuture;
                    if (fastMode && !fileUris.isEmpty()) {
                        Path workspaceRoot = workspace.getRootPath();
                        setupFuture = fastModeProjectManager.ensureModuleSetup(
                                workspaceRoot, fileUris.get(0), jdtls, progressMonitor);
                    } else {
                        setupFuture = CompletableFuture.completedFuture(null);
                    }

                    return setupFuture
                            .whenComplete((v, ex) -> {
                                if (progressMonitor != null) {
                                    progressMonitor.setComplete();
                                }
                            })
                            .thenCompose(v -> {
                                CompletableFuture<List<Map<String, Object>>> chain =
                                        CompletableFuture.completedFuture(new ArrayList<>());
                                for (String uri : fileUris) {
                                    chain = chain.thenCompose(results -> {
                                        Object args = argsBuilder.apply(uri);
                                        @SuppressWarnings("unchecked")
                                        List<Object> argList = args instanceof List
                                                ? (List<Object>) args
                                                : List.of(args);
                                        return jdtls.executeCommand(commandId, argList)
                                                .thenApply(result -> {
                                                    Map<String, Object> entry = new LinkedHashMap<>();
                                                    entry.put("fileUri", uri);
                                                    entry.put("result", result);
                                                    results.add(entry);
                                                    return results;
                                                });
                                    });
                                }
                                return chain;
                            })
                            .thenApply(this::formatResult);
                })
                .exceptionally(ex -> {
                    LOG.errorf(ex, "Failed to execute batch command %s", commandId);
                    return "Error executing " + commandId + ": " + ex.getMessage();
                });
    }

    String formatResult(Object result) {
        if (result == null) {
            return "No result";
        }
        if (result instanceof String s) {
            return s;
        }
        try {
            return GSON.toJson(result);
        } catch (Exception e) {
            return result.toString();
        }
    }
}
