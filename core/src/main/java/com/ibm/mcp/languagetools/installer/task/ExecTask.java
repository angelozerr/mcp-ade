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
package com.ibm.mcp.languagetools.installer.task;

import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.installer.InstallerContext;
import com.ibm.mcp.languagetools.utils.OSUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class ExecTask extends InstallerTask {
    private final String command;
    private final Integer timeout;
    private final String workingDir;

    public ExecTask(String name, InstallerTask onSuccess, String command, Integer timeout, String workingDir) {
        super(name, onSuccess);
        this.command = command;
        this.timeout = timeout;
        this.workingDir = workingDir;
    }

    @Override
    protected boolean run(InstallerContext context) {
        String resolvedCommand = context.resolveVariables(command);
        context.traceInfo("Executing: " + resolvedCommand);

        try {
            ProcessBuilder pb;
            if (OSUtils.isWindows()) {
                pb = new ProcessBuilder("cmd", "/c", resolvedCommand);
            } else {
                pb = new ProcessBuilder("sh", "-c", resolvedCommand);
            }
            pb.redirectErrorStream(false);

            if (workingDir != null) {
                String resolvedDir = context.resolveVariables(workingDir);
                Path dirPath = Paths.get(resolvedDir);
                Files.createDirectories(dirPath);
                pb.directory(dirPath.toFile());
                context.traceInfo("Working directory: " + resolvedDir);
            }

            Process process = pb.start();

            Thread stdoutThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        context.traceInfo(line);
                    }
                } catch (Exception e) {
                    // ignore
                }
            });

            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        context.traceError(line);
                    }
                } catch (Exception e) {
                    // ignore
                }
            });

            stdoutThread.start();
            stderrThread.start();

            boolean finished;
            if (timeout != null) {
                finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    context.traceError("Command timed out after " + timeout + "ms");
                    return false;
                }
            } else {
                process.waitFor();
                finished = true;
            }

            stdoutThread.join(2000);
            stderrThread.join(2000);

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                context.traceInfo("Command completed successfully (exit code 0)");
                return true;
            } else {
                context.traceError("Command failed with exit code " + exitCode);
                return false;
            }
        } catch (Exception e) {
            context.traceError("Failed to execute command: " + e.getMessage());
            return false;
        }
    }

    public static class Factory extends InstallerTaskFactoryBase {
        @Override
        public String getType() {
            return "exec";
        }

        @Override
        protected String getDefaultName() {
            return "Execute command";
        }

        @Override
        protected InstallerTask create(String name, InstallerTask onSuccess, JsonObject json) {
            String command = OSUtils.getStringFromOs(json, "command");
            Integer timeout = json.has("timeout") ? json.get("timeout").getAsInt() : null;
            String workingDir = json.has("workingDir") ? json.get("workingDir").getAsString() : null;
            return new ExecTask(name, onSuccess, command, timeout, workingDir);
        }
    }
}
