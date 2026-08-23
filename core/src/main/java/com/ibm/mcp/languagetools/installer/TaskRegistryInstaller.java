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
package com.ibm.mcp.languagetools.installer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.installer.task.DownloadTask;
import com.ibm.mcp.languagetools.installer.task.InstallerTask;
import com.ibm.mcp.languagetools.installer.task.InstallerTaskRegistry;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressStep;
import com.ibm.mcp.languagetools.utils.OSUtils;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server installer that uses task registry to execute installer.json.
 * Uses Gson exclusively for JSON parsing.
 */
public class TaskRegistryInstaller implements ServerInstaller {
    private static final Logger LOG = Logger.getLogger(TaskRegistryInstaller.class);

    // JSON field names
    private static final String FIELD_PROPERTIES = "properties";
    private static final String FIELD_CHECK = "check";
    private static final String FIELD_RUN = "run";

    private final InstallableConfig config;
    private final JsonElement installerConfigJson;
    private final InstallerTaskRegistry registry;
    private final Gson gson;
    private final AtomicReference<InstallationStatus> status = new AtomicReference<>(InstallationStatus.NOT_INSTALLED);

    public TaskRegistryInstaller(InstallableConfig config) {
        this(config, config.getInstallerConfig());
    }

    public TaskRegistryInstaller(InstallableConfig config, JsonElement installerConfigJson) {
        this.config = config;
        this.installerConfigJson = installerConfigJson;
        this.registry = new InstallerTaskRegistry();
        this.gson = new Gson();
    }

    /**
     * Set installation status and notify context.
     */
    private void setStatus(InstallerContext context, InstallationStatus installStatus) {
        status.set(installStatus);
        context.notifyInstallationStatusChange(installStatus);
    }

    @Override
    public CompletableFuture<InstallResult> checkInstalled(InstallerContext context) {
        ClassLoader callerClassLoader = Thread.currentThread().getContextClassLoader();
        return CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().setContextClassLoader(callerClassLoader);
            try {
                if (installerConfigJson == null || !installerConfigJson.isJsonObject()) {
                    return new InstallResult(null, null, InstallationStatus.NOT_INSTALLED);
                }

                JsonObject installerConfig = installerConfigJson.getAsJsonObject();
                loadProperties(installerConfig, context);

                if (!installerConfig.has(FIELD_CHECK)) {
                    return new InstallResult(null, null, InstallationStatus.NOT_INSTALLED);
                }

                context.getProgress().beginStep(ProgressStep.CHECKING);
                InstallerTask checkTask = parseTaskNode(installerConfig.get(FIELD_CHECK));
                if (checkTask != null && checkTask.execute(context)) {
                    String command = extractCommand(context, installerConfig);
                    setStatus(context, InstallationStatus.ALREADY_INSTALLED);
                    return new InstallResult(context.getInstallDir(), command, InstallationStatus.ALREADY_INSTALLED);
                }

                return new InstallResult(null, null, InstallationStatus.NOT_INSTALLED);
            } catch (Exception e) {
                LOG.debugf(e, "Check failed for %s", config.getServerId());
                return new InstallResult(null, null, InstallationStatus.NOT_INSTALLED);
            }
        });
    }

    @Override
    public CompletableFuture<InstallResult> ensureInstalled(InstallerContext context) {
        ClassLoader callerClassLoader = Thread.currentThread().getContextClassLoader();
        return CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().setContextClassLoader(callerClassLoader);
            long startTime = System.currentTimeMillis();
            try {
                if (installerConfigJson == null || !installerConfigJson.isJsonObject()) {
                    throw new IllegalStateException("No installer configuration found for " + config.getServerId());
                }

                JsonObject installerConfig = installerConfigJson.getAsJsonObject();

                context.traceInfo("Starting installation for: " + config.getName());

                // Load properties into context
                loadProperties(installerConfig, context);

                // Check if already installed (skip when force install)
                if (!context.isForceInstall() && installerConfig.has(FIELD_CHECK)) {
                    context.getProgress().beginStep(ProgressStep.CHECKING);
                    InstallerTask checkTask = parseTaskNode(installerConfig.get(FIELD_CHECK));
                    if (checkTask != null && checkTask.execute(context)) {
                        String command = extractCommand(context, installerConfig);
                        context.traceInfo("Server already installed");
                        setStatus(context, InstallationStatus.ALREADY_INSTALLED);
                        return new InstallResult(context.getInstallDir(), command, InstallationStatus.ALREADY_INSTALLED);
                    }
                }

                // Not installed - run installation
                context.getProgress().beginStep(ProgressStep.INSTALLING);
                setStatus(context, InstallationStatus.INSTALLING);
                context.traceInfo("Installing server...");

                if (!installerConfig.has(FIELD_RUN)) {
                    String runtimeName = installerConfig.has("name") ? installerConfig.get("name").getAsString() : config.getName();
                    String runtimeUrl = installerConfig.has("url") ? installerConfig.get("url").getAsString() : null;
                    StringBuilder message = new StringBuilder();
                    message.append("'").append(runtimeName).append("' is not installed and cannot be auto-installed.");
                    if (runtimeUrl != null) {
                        message.append(" Please install it manually from: ").append(runtimeUrl);
                    }
                    throw new IllegalStateException(message.toString());
                }

                InstallerTask runTask = parseTaskNode(installerConfig.get(FIELD_RUN));
                if (runTask == null) {
                    JsonObject runObj = installerConfig.getAsJsonObject(FIELD_RUN);
                    String taskType = runObj != null && runObj.size() > 0 ? runObj.keySet().iterator().next() : "unknown";
                    throw new IllegalStateException("Failed to create run task of type '" + taskType + "' from installer.json (registered factories: " + registry.getRegisteredTypes() + ")");
                }

                boolean success = runTask.execute(context);
                if (!success) {
                    setStatus(context, InstallationStatus.FAILED);
                    throw new IllegalStateException("Task '" + runTask.getName() + "' failed");
                }

                // Extract command after installation
                String command = extractCommand(context, installerConfig);

                setStatus(context, InstallationStatus.INSTALLED);
                long elapsedMs = System.currentTimeMillis() - startTime;
                context.traceInfo(String.format("Installation completed successfully in %d ms", elapsedMs));

                return new InstallResult(context.getInstallDir(), command, InstallationStatus.INSTALLED);

            } catch (java.util.concurrent.CancellationException e) {
                setStatus(context, InstallationStatus.STOPPED);
                context.traceError("Installation cancelled by user");
                throw new InstallationException("Installation cancelled", e);
            } catch (Exception e) {
                LOG.errorf(e, "Installation failed for %s", config.getServerId());
                setStatus(context, InstallationStatus.FAILED);
                context.traceError("Installation failed: " + e.getMessage());
                Throwable cause = e.getCause();
                if (cause != null && cause != e) {
                    context.traceError("Cause: " + cause.getMessage());
                }
                throw new InstallationException(e.getMessage(), e);
            }
        });
    }

    @Override
    public InstallationStatus getStatus() {
        return status.get();
    }

    @Override
    public void stop() {
        // Cancellation is handled via InstallerContext.checkCanceled()
        status.set(InstallationStatus.STOPPED);
    }

    private void loadProperties(JsonObject installerConfig, InstallerContext context) {
        if (!installerConfig.has(FIELD_PROPERTIES)) {
            return;
        }
        JsonElement propsElement = installerConfig.get(FIELD_PROPERTIES);
        if (!propsElement.isJsonObject()) {
            return;
        }
        JsonObject properties = propsElement.getAsJsonObject();
        for (var entry : properties.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive()) {
                String resolved = context.resolveVariables(value.getAsString());
                context.setVariable(entry.getKey(), resolved);
            }
        }
    }

    private InstallerTask parseTaskNode(JsonElement taskNode) {
        if (taskNode == null || !taskNode.isJsonObject()) {
            return null;
        }

        JsonObject taskObj = taskNode.getAsJsonObject();

        // Find the task type (first key in the object)
        if (taskObj.size() == 0) {
            return null;
        }

        String taskType = taskObj.keySet().iterator().next();
        JsonElement taskConfig = taskObj.get(taskType);

        return registry.createTask(taskType, taskConfig);
    }

    /**
     * Extracts the server command.
     * First checks if ConfigureServerTask already set it in context,
     * otherwise walks the run task tree to find a configureServer node
     * and resolves its command (avoids duplicating configureServer in check and run).
     */
    private String extractCommand(InstallerContext context, JsonObject installerConfig) {
        String command = context.getVariable("SERVER_COMMAND");
        if (command != null) {
            return command;
        }

        if (installerConfig.has(FIELD_RUN)) {
            command = findConfigureServerCommand(installerConfig.get(FIELD_RUN), context);
        }

        if (command == null) {
            LOG.warnf("No command configured for %s", config.getServerId());
        }

        return command;
    }

    /**
     * Walk the task tree to find a configureServer node and resolve its command.
     * Also extracts variables from parent tasks (e.g. output.dir from download tasks)
     * so that ${output.dir} can be resolved in the configureServer command.
     */
    private String findConfigureServerCommand(JsonElement taskNode, InstallerContext context) {
        if (taskNode == null || !taskNode.isJsonObject()) {
            return null;
        }
        JsonObject taskObj = taskNode.getAsJsonObject();
        if (taskObj.isEmpty()) {
            return null;
        }

        String taskType = taskObj.keySet().iterator().next();
        JsonElement taskConfigElement = taskObj.get(taskType);
        if (taskConfigElement == null || !taskConfigElement.isJsonObject()) {
            return null;
        }
        JsonObject taskConfig = taskConfigElement.getAsJsonObject();

        if ("configureServer".equals(taskType)) {
            String cmd = OSUtils.getStringFromOs(taskConfig, "command");
            if (cmd != null) {
                return context.resolveVariables(cmd);
            }
        }

        // Extract variables from download tasks so configureServer can reference them
        if ("download".equals(taskType) && taskConfig.has("output")) {
            JsonObject output = taskConfig.getAsJsonObject("output");
            if (output.has("dir")) {
                context.setVariable("output.dir", context.resolveVariables(output.get("dir").getAsString()));
            }
            if (output.has("file")) {
                JsonObject file = output.getAsJsonObject("file");
                if (file.has("name")) {
                    String fileName = OSUtils.getStringFromOs(file, "name");
                    if (fileName != null) {
                        context.setVariable("output.file.name", context.resolveVariables(fileName));
                    }
                }
            }
        }

        if (taskConfig.has("onSuccess")) {
            return findConfigureServerCommand(taskConfig.get("onSuccess"), context);
        }

        return null;
    }

    /**
     * Configure progress steps from installer.json task tree.
     * Walks the check and run task nodes (including onSuccess chains)
     * and adds a step for each declared task.
     *
     * @param progress the progress monitor to configure
     * @param installerConfig the installer.json content
     * @param force true to skip the check task
     */
    public static void configureInstallerSteps(ProgressMonitor progress, JsonElement installerConfig, boolean force) {
        if (installerConfig == null || !installerConfig.isJsonObject()) {
            return;
        }
        JsonObject config = installerConfig.getAsJsonObject();

        if (!force && config.has(FIELD_CHECK)) {
            collectTaskSteps(config.get(FIELD_CHECK), progress);
        }

        if (config.has(FIELD_RUN)) {
            collectTaskSteps(config.get(FIELD_RUN), progress);
        }
    }

    private static void collectTaskSteps(JsonElement taskNode, ProgressMonitor progress) {
        if (taskNode == null || !taskNode.isJsonObject()) {
            return;
        }
        JsonObject taskObj = taskNode.getAsJsonObject();
        if (taskObj.isEmpty()) {
            return;
        }

        String taskType = taskObj.keySet().iterator().next();
        JsonElement taskConfigElement = taskObj.get(taskType);
        if (taskConfigElement == null || !taskConfigElement.isJsonObject()) {
            return;
        }
        JsonObject taskConfig = taskConfigElement.getAsJsonObject();

        String name = taskConfig.has("name") ? taskConfig.get("name").getAsString() : taskType;
        double weight = getTaskWeight(taskType);
        progress.addStep(name, weight);

        if ("download".equals(taskType)) {
            progress.addStep(DownloadTask.getExtractStepName(name), 5.0);
        }

        if (taskConfig.has("onSuccess")) {
            collectTaskSteps(taskConfig.get("onSuccess"), progress);
        }
    }

    private static double getTaskWeight(String taskType) {
        return switch (taskType) {
            case "download" -> 20.0;
            case "extractResource" -> 15.0;
            case "copy" -> 10.0;
            case "fileExists" -> 1.0;
            case "configureServer" -> 1.0;
            default -> 5.0;
        };
    }
}
