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

import org.eclipse.mcp.ade.utils.OSUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralized application environment that aggregates PATH entries and environment
 * variables from all registered runtimes. Rebuilt automatically when runtime states change.
 */
public class ApplicationEnvironment {

    private static final Logger LOG = Logger.getLogger(ApplicationEnvironment.class);

    private static final String PATH_ENV = "PATH";

    public record PathEntry(String directory, String source, String sourceType, boolean exists) {}

    public record EnvEntry(String name, String value, String source, String sourceType) {}

    private volatile List<PathEntry> pathEntries = Collections.emptyList();
    private volatile Map<String, EnvEntry> envEntries = Collections.emptyMap();
    private volatile Map<String, String> env;

    /**
     * Returns the application PATH string, combining all installed runtime directories
     * with the system PATH. Falls back to the system PATH if not yet built.
     */
    public String getPath() {
        Map<String, String> e = env;
        return e != null ? e.get(PATH_ENV) : System.getenv(PATH_ENV);
    }

    /**
     * Returns the full application environment map (PATH + runtime env vars).
     * Falls back to an empty map if not yet built.
     */
    public Map<String, String> getEnv() {
        Map<String, String> e = env;
        return e != null ? e : Collections.emptyMap();
    }

    /**
     * Returns a new mutable env map with additional directories prepended to PATH.
     * The returned map contains only PATH, not the full application env.
     */
    public Map<String, String> createEnvWithPath(String... prependDirs) {
        Map<String, String> result = new HashMap<>();
        result.put(PATH_ENV, buildPath(prependDirs));
        return result;
    }

    /**
     * Prepends a directory to PATH in an existing env map.
     * If the map has no PATH, uses the application PATH as base.
     */
    public void prependToPath(Map<String, String> env, String dir) {
        String existing = env.get(PATH_ENV);
        if (existing != null && !existing.isEmpty()) {
            env.put(PATH_ENV, dir + File.pathSeparator + existing);
        } else {
            env.put(PATH_ENV, buildPath(dir));
        }
    }

    /**
     * Builds a PATH string with the given directories prepended to the application PATH.
     */
    public String buildPath(String... prependDirs) {
        StringBuilder sb = new StringBuilder();
        if (prependDirs != null) {
            for (String dir : prependDirs) {
                if (dir != null && !dir.isEmpty()) {
                    if (sb.length() > 0) sb.append(File.pathSeparator);
                    sb.append(dir);
                }
            }
        }
        String basePath = getPath();
        if (basePath != null && !basePath.isEmpty()) {
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(basePath);
        }
        return sb.toString();
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

        Set<String> installerCommands = collectInstallerCommands(runtimes);

        for (RuntimeConfig runtime : runtimes.values()) {
            addRuntimeEntries(runtime, newPathEntries, newEnvEntries);
        }

        String systemPath = System.getenv(PATH_ENV);
        if (systemPath != null) {
            for (String dir : systemPath.split(File.pathSeparator)) {
                if (!dir.isEmpty() && !containsAnyCommand(dir, installerCommands)) {
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

        Map<String, String> newEnv = new LinkedHashMap<>();
        newEnv.put(PATH_ENV, sb.toString());
        for (Map.Entry<String, EnvEntry> entry : newEnvEntries.entrySet()) {
            newEnv.put(entry.getKey(), entry.getValue().value());
        }
        this.env = Collections.unmodifiableMap(newEnv);

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

        RuntimeSource pref = runtime.getSourceMode();
        if (pref != RuntimeSource.SYSTEM) {
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

    private Set<String> collectInstallerCommands(Map<String, RuntimeConfig> runtimes) {
        Set<String> commands = new HashSet<>();
        for (RuntimeConfig runtime : runtimes.values()) {
            if (runtime.getSourceMode() == RuntimeSource.EMBEDDED) {
                String command = TaskRegistryInstaller.extractCheckCommand(runtime.getInstallerConfig());
                if (command != null) {
                    commands.add(command);
                }
            }
        }
        return commands;
    }

    private static boolean containsAnyCommand(String dir, Set<String> commands) {
        if (commands.isEmpty()) {
            return false;
        }
        for (String cmd : commands) {
            if (Files.exists(Path.of(dir, cmd)) ||
                    (OSUtils.isWindows() && Files.exists(Path.of(dir, cmd + ".exe")))) {
                return true;
            }
        }
        return false;
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
