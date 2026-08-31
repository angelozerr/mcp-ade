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
 * MCP tools for Java framework analysis via JDT.LS delegate command handlers.
 */
@ApplicationScoped
public class JavaFrameworkTools {

    @Inject
    JdtlsCommandExecutor executor;

    private static final Map<String, String> KIND_TO_COMMAND = Map.of(
            "endpoints", JdtlsCommands.GET_HTTP_ENDPOINTS,
            "jpa", JdtlsCommands.GET_JPA_MODEL,
            "di", JdtlsCommands.GET_DI_REGISTRATIONS
    );

    @Tool(name = "java_get_framework_info",
          description = "Get framework-specific information from a Java project. "
                  + "kind: 'endpoints' (HTTP/REST routes), 'jpa' (JPA entity model), "
                  + "'di' (dependency injection registrations - Spring, Jakarta CDI)")
    public CompletableFuture<String> getFrameworkInfo(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Kind: 'endpoints', 'jpa', or 'di'") String kind,
            @ToolArg(description = JavaToolArgDescriptions.SEARCH_SCOPE, required = false) String scope,
            @ToolArg(description = JavaToolArgDescriptions.PROJECT_NAME, required = false) String projectName,
            Cancellation cancellation,
            Progress progress) {
        String commandId = KIND_TO_COMMAND.get(kind);
        if (commandId == null) {
            return CompletableFuture.completedFuture(
                    "Error: invalid kind '" + kind + "'. Valid values: endpoints, jpa, di");
        }
        Map<String, Object> args = new HashMap<>();
        RefactoringHelper.putScope(args, scope, projectName);
        return executor.executeCommand(cwd, commandId, args, cancellation, progress);
    }
}
