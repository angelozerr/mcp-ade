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
package org.eclipse.mcp.ade.runtime;

import org.eclipse.mcp.ade.installer.InstallerContext;
import org.eclipse.mcp.ade.installer.InstallationStatus;
import org.eclipse.mcp.ade.installer.TaskRegistryInstaller;
import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.jboss.logging.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized application environment that aggregates PATH entries and environment
 * variables from all registered runtimes. Rebuilt automatically when runtime states change.
 */
public class ApplicationEnvironment {

    private static final Logger LOG = Logger.getLogger(ApplicationEnvironment.class);

    public record PathEntry(String directory, String source, String sourceType, boolean exists) {}

    public record EnvEntry(String name, String value, String source, String sourceType) {}

    private volatile List<PathEntry> pathEntries = Collections.emptyList();
    private volatile Map<String, EnvEntry> envEntries = Collections.emptyMap();
    private volatile String applicationPath;

    /**
     * Returns the application PATH string, combining all installed runtime directories
     * with the system PATH. Falls back to the system PATH if not yet built.
     */
    public String getPath() {
        String cached = applicationPath;
        return cached != null ? cached : System.getenv("PATH");
    }

    public List<PathEntry> getPathEntries() {
        return pathEntries;
    }

    public Map<String, EnvEntry> getEnvEntries() {
        return envEntries;
    }

    /**
     * Rebuilds the application environment from the given runtimes.
     */
    public void rebuild(Map<String, RuntimeConfig> runtimes) {
        List<PathEntry> newPathEntries = new ArrayList<>();
        Map<String, EnvEntry> newEnvEntries = new LinkedHashMap<>();

        for (RuntimeConfig runtime : runtimes.values()) {
            addRuntimeEntries(runtime, newPathEntries, newEnvEntries);
        }

        String systemPath = System.getenv("PATH");
        if (systemPath != null) {
            for (String dir : systemPath.split(File.pathSeparator)) {
                if (!dir.isEmpty()) {
                    newPathEntries.add(new PathEntry(dir, "System", "system",
                            Files.isDirectory(Path.of(dir))));
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (PathEntry entry : newPathEntries) {
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(entry.directory());
        }

        this.pathEntries = Collections.unmodifiableList(newPathEntries);
        this.envEntries = Collections.unmodifiableMap(newEnvEntries);
        this.applicationPath = sb.toString();

        long runtimeEntries = newPathEntries.stream().filter(e -> !"system".equals(e.sourceType())).count();
        LOG.infof("Application environment rebuilt: %d runtime PATH entries, %d env vars",
                runtimeEntries, newEnvEntries.size());
    }

    private void addRuntimeEntries(RuntimeConfig runtime, List<PathEntry> pathEntries,
                                    Map<String, EnvEntry> envEntries) {
        InstallationStatus status = runtime.getStatus();
        if (status != InstallationStatus.ALREADY_INSTALLED && status != InstallationStatus.INSTALLED) {
            return;
        }

        String runtimeName = runtime.getName() != null ? runtime.getName() : runtime.getRuntimeId();
        String sourceType = "runtime:" + runtime.getRuntimeId();

        RuntimeSourcePreference pref = runtime.getSourcePreference();
        if (pref != RuntimeSourcePreference.PATH) {
            String commandDir = getCommandDir(runtime);
            if (commandDir != null) {
                pathEntries.add(new PathEntry(commandDir, runtimeName, sourceType,
                        Files.isDirectory(Path.of(commandDir))));
            }
        }

        Map<String, String> resolvedEnv = runtime.getResolvedEnv();
        for (Map.Entry<String, String> entry : resolvedEnv.entrySet()) {
            envEntries.put(entry.getKey(), new EnvEntry(entry.getKey(), entry.getValue(),
                    runtimeName, sourceType));
        }
    }

    private String getCommandDir(RuntimeConfig runtime) {
        String resolvedPath = runtime.getResolvedPath();
        if (resolvedPath != null) {
            Path parentDir = Path.of(resolvedPath).getParent();
            if (parentDir != null) {
                return parentDir.toString();
            }
        }

        InstallerContext ctx = new InstallerContext(runtime, ProgressMonitor.none());
        String commandDir = TaskRegistryInstaller.extractCommandDir(runtime.getInstallerConfig(), ctx);
        if (commandDir != null && Files.isDirectory(Path.of(commandDir))) {
            return commandDir;
        }

        return null;
    }
}
