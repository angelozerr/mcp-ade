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
package org.eclipse.mcp.ade.extensions.jdtls.tools;

import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for Java project structure analysis via JDT.LS delegate command handlers.
 *
 * <p>Tools adapted from <a href="https://github.com/pzalutski-pixel/javalens-mcp">javalens-mcp</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
@ApplicationScoped
public class JavaProjectTools {

    @Inject
    JdtlsCommandExecutor executor;

    @Tool(name = "java_get_project_structure",
          description = "Get the package hierarchy and file structure of a Java project")
    public CompletableFuture<String> getProjectStructure(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Name of the Java project to inspect (defaults to first Java project in workspace)", required = false) String projectName,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> args = new HashMap<>();
        if (projectName != null) {
            args.put("projectName", projectName);
        }
        return executor.executeCommand(cwd, JdtlsCommands.GET_PROJECT_STRUCTURE,
                args,
                cancellation, progress);
    }

    @Tool(name = "java_get_classpath_info",
          description = "Get the classpath entries of a Java project")
    public CompletableFuture<String> getClasspathInfo(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            Cancellation cancellation,
            Progress progress) {
        return executor.executeCommand(cwd, JdtlsCommands.GET_CLASSPATH_INFO,
                Map.of(),
                cancellation, progress);
    }
}
