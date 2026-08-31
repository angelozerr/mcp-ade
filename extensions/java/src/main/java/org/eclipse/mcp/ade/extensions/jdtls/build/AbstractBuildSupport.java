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
package org.eclipse.mcp.ade.extensions.jdtls.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared infrastructure for build support implementations (Maven, Gradle).
 *
 * <p>Provides common utilities: build-tool executable discovery with wrapper
 * walk-up, Windows {@code cmd /c} command wrapping, standard Java source root
 * detection, and process creation with debug-environment cleanup.</p>
 */
public abstract class AbstractBuildSupport implements BuildSupport {

    protected abstract String unixWrapperName();

    protected abstract String windowsWrapperName();

    protected abstract String unixSystemName();

    protected abstract String windowsSystemName();

    protected abstract String buildToolOptsVar();

    protected static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    protected Path findBuildToolExecutable(Path projectRoot) {
        String wrapperName = isWindows() ? windowsWrapperName() : unixWrapperName();
        Path dir = projectRoot.toAbsolutePath().normalize();
        while (dir != null) {
            Path wrapper = dir.resolve(wrapperName);
            if (Files.isRegularFile(wrapper)) {
                return wrapper;
            }
            dir = dir.getParent();
        }
        return Path.of(isWindows() ? windowsSystemName() : unixSystemName());
    }

    protected static List<String> buildCommand(Path executable, String... args) {
        List<String> command = new ArrayList<>();
        String exec = executable.toString();
        if (exec.endsWith(".cmd") || exec.endsWith(".bat")) {
            command.add("cmd");
            command.add("/c");
        }
        command.add(exec);
        command.addAll(List.of(args));
        return command;
    }

    protected ProcessBuilder createProcess(Path executable, Path workingDir, String... args) {
        List<String> command = buildCommand(executable, args);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        BuildSupport.cleanDebugEnvironment(pb, buildToolOptsVar());
        return pb;
    }

    protected static int waitForProcess(Process process) throws BuildSupportException {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BuildSupportException("Build tool process interrupted");
        }
    }

    protected List<String> detectSourceRoots(Path moduleDir) {
        List<String> roots = new ArrayList<>();
        String[] candidates = {
                "src/main/java",
                "src/main/resources",
                "src/test/java",
                "src/test/resources"
        };
        for (String candidate : candidates) {
            if (Files.isDirectory(moduleDir.resolve(candidate))) {
                roots.add(candidate);
            }
        }
        if (roots.isEmpty() && Files.isDirectory(moduleDir.resolve("src"))) {
            roots.add("src");
        }
        return roots;
    }
}
