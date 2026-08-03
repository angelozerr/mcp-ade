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
 * MCP tools for Java reference search via JDT.LS delegate command handlers.
 */
@ApplicationScoped
public class JavaReferenceSearchTools {

    @Inject
    JdtlsCommandExecutor executor;

    private static final Map<String, String> KIND_TO_COMMAND = Map.of(
            "cast", JdtlsCommands.FIND_CASTS,
            "catch", JdtlsCommands.FIND_CATCH_BLOCKS,
            "instanceof", JdtlsCommands.FIND_INSTANCEOF_CHECKS,
            "throws", JdtlsCommands.FIND_THROWS_DECLARATIONS,
            "type_argument", JdtlsCommands.FIND_TYPE_ARGUMENTS
    );

    @Tool(name = "java_find_type_usages",
          description = "Find specific usages of a Java type by kind: 'cast' (cast expressions), "
                  + "'catch' (catch blocks), 'instanceof' (instanceof checks), 'throws' (throws declarations), "
                  + "'type_argument' (generic type arguments)")
    public CompletableFuture<String> findTypeUsages(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Fully qualified name of the type (e.g., 'java.lang.String')") String fullyQualifiedName,
            @ToolArg(description = "Usage kind: 'cast', 'catch', 'instanceof', 'throws', or 'type_argument'") String kind,
            Cancellation cancellation,
            Progress progress) {
        String commandId = KIND_TO_COMMAND.get(kind);
        if (commandId == null) {
            return CompletableFuture.completedFuture(
                    "Error: invalid kind '" + kind + "'. Valid values: cast, catch, instanceof, throws, type_argument");
        }
        return executor.executeCommand(cwd, commandId,
                RefactoringHelper.fqnParams(fullyQualifiedName),
                cancellation, progress);
    }
}
