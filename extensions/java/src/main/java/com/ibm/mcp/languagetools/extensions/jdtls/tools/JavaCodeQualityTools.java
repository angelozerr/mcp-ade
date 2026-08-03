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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for Java code quality analysis via JDT.LS delegate command handlers.
 *
 * <p>Tools adapted from <a href="https://github.com/pzalutski-pixel/javalens-mcp">javalens-mcp</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
@ApplicationScoped
public class JavaCodeQualityTools {

    @Inject
    JdtlsCommandExecutor executor;

    @Tool(name = "java_find_possible_bugs",
          description = "Find potential bug patterns in a Java file")
    public CompletableFuture<String> findPossibleBugs(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri,
            @ToolArg(description = JavaToolArgDescriptions.FILE_URIS, required = false) List<String> fileUris,
            Cancellation cancellation,
            Progress progress) {
        List<String> uris = RefactoringHelper.resolveFileUris(fileUri, fileUris);
        if (uris.size() > 1) {
            return executor.executeBatchCommand(cwd, JdtlsCommands.FIND_POSSIBLE_BUGS, uris,
                    uri -> Map.of("uri", uri), cancellation, progress);
        }
        return executor.executeCommand(cwd, JdtlsCommands.FIND_POSSIBLE_BUGS,
                Map.of("uri", fileUri),
                cancellation, progress);
    }

    @Tool(name = "java_find_circular_dependencies",
          description = "Find circular package dependencies in a Java project")
    public CompletableFuture<String> findCircularDependencies(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            Cancellation cancellation,
            Progress progress) {
        return executor.executeCommand(cwd, JdtlsCommands.FIND_CIRCULAR_DEPENDENCIES,
                Map.of(),
                cancellation, progress);
    }

    @Tool(name = "java_code_quality_report",
          description = "Run all quality checks on a Java file in one call (unused code, bugs, complexity)")
    public CompletableFuture<String> codeQualityReport(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> uriArgs = Map.of("uri", fileUri);
        CompletableFuture<Object> unused = executor.executeCommandRaw(cwd, JdtlsCommands.FIND_UNUSED_CODE, uriArgs);
        CompletableFuture<Object> bugs = executor.executeCommandRaw(cwd, JdtlsCommands.FIND_POSSIBLE_BUGS, uriArgs);
        CompletableFuture<Object> complexity = executor.executeCommandRaw(cwd, JdtlsCommands.GET_COMPLEXITY_METRICS, uriArgs);

        return CompletableFuture.allOf(unused, bugs, complexity)
                .thenApply(v -> {
                    Map<String, Object> report = new LinkedHashMap<>();
                    report.put("fileUri", fileUri);
                    report.put("unusedCode", unused.join());
                    report.put("possibleBugs", bugs.join());
                    report.put("complexity", complexity.join());
                    return executor.formatResult(report, cwd);
                });
    }
}
