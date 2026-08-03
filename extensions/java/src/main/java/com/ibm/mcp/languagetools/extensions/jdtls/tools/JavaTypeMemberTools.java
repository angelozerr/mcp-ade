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

import com.ibm.mcp.languagetools.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for Java type member inspection via JDT.LS delegate command handlers.
 *
 * <p>Tools adapted from <a href="https://github.com/pzalutski-pixel/javalens-mcp">javalens-mcp</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
@ApplicationScoped
public class JavaTypeMemberTools {

    @Inject
    JdtlsCommandExecutor executor;

    @Tool(name = "java_get_type_members",
          description = "Get all members (methods, fields, nested types) of a Java type")
    public CompletableFuture<String> getTypeMembers(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Fully qualified name of the type") String fullyQualifiedName,
            Cancellation cancellation,
            Progress progress) {
        return executor.executeCommand(cwd, JdtlsCommands.GET_TYPE_MEMBERS,
                RefactoringHelper.fqnParams(fullyQualifiedName),
                cancellation, progress);
    }

    @Tool(name = "java_get_dependency_graph",
          description = "Get the import-based dependency graph for a Java project")
    public CompletableFuture<String> getDependencyGraph(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Maximum number of edges to return (0 or omit for unlimited). When truncated, response includes truncated=true",
                     required = false) Integer maxEdges,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = new java.util.HashMap<>();
        if (maxEdges != null && maxEdges > 0) {
            params.put("maxEdges", maxEdges);
        }
        return executor.executeCommand(cwd, JdtlsCommands.GET_DEPENDENCY_GRAPH, params, cancellation, progress);
    }
}
