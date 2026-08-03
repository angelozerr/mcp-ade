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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for Java code search via JDT.LS delegate command handlers.
 *
 * <p>Tools adapted from <a href="https://github.com/pzalutski-pixel/javalens-mcp">javalens-mcp</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
@ApplicationScoped
public class JavaCodeSearchTools {

    @Inject
    JdtlsCommandExecutor executor;

    @Tool(name = "java_find_field_writes",
          description = "Find all write accesses to a field")
    public CompletableFuture<String> findFieldWrites(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = JavaToolArgDescriptions.SEARCH_SCOPE, required = false) String scope,
            @ToolArg(description = JavaToolArgDescriptions.PROJECT_NAME, required = false) String projectName,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(fileUri, line, character);
        RefactoringHelper.putScope(params, scope, projectName);
        return executor.executeCommand(cwd, JdtlsCommands.FIND_FIELD_WRITES, params, cancellation, progress);
    }

    @Tool(name = "java_find_tests",
          description = "Find test methods in a Java file (JUnit 4/5, TestNG)")
    public CompletableFuture<String> findTests(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri,
            @ToolArg(description = JavaToolArgDescriptions.FILE_URIS, required = false) List<String> fileUris,
            Cancellation cancellation,
            Progress progress) {
        List<String> uris = RefactoringHelper.resolveFileUris(fileUri, fileUris);
        if (uris.size() > 1) {
            return executor.executeBatchCommand(cwd, JdtlsCommands.FIND_TESTS, uris,
                    uri -> Map.of("uri", uri), cancellation, progress);
        }
        return executor.executeCommand(cwd, JdtlsCommands.FIND_TESTS,
                Map.of("uri", fileUri),
                cancellation, progress);
    }

    @Tool(name = "java_find_affected_tests",
          description = "Find tests transitively affected by changes to a symbol")
    public CompletableFuture<String> findAffectedTests(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = JavaToolArgDescriptions.MAX_RESULTS, required = false) Integer maxResults,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(fileUri, line, character);
        RefactoringHelper.putMaxResults(params, maxResults);
        return executor.executeCommand(cwd, JdtlsCommands.FIND_AFFECTED_TESTS, params, cancellation, progress);
    }

    @Tool(name = "java_find_unused_code",
          description = "Find unused code in a Java file (imports, private fields, methods, variables)")
    public CompletableFuture<String> findUnusedCode(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri,
            @ToolArg(description = JavaToolArgDescriptions.FILE_URIS, required = false) List<String> fileUris,
            Cancellation cancellation,
            Progress progress) {
        List<String> uris = RefactoringHelper.resolveFileUris(fileUri, fileUris);
        if (uris.size() > 1) {
            return executor.executeBatchCommand(cwd, JdtlsCommands.FIND_UNUSED_CODE, uris,
                    uri -> Map.of("uri", uri), cancellation, progress);
        }
        return executor.executeCommand(cwd, JdtlsCommands.FIND_UNUSED_CODE,
                Map.of("uri", fileUri),
                cancellation, progress);
    }

    @Tool(name = "java_find_unreachable_code",
          description = "Find unreachable code in a Java file (dead code after return/throw)")
    public CompletableFuture<String> findUnreachableCode(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri,
            @ToolArg(description = JavaToolArgDescriptions.FILE_URIS, required = false) List<String> fileUris,
            Cancellation cancellation,
            Progress progress) {
        List<String> uris = RefactoringHelper.resolveFileUris(fileUri, fileUris);
        if (uris.size() > 1) {
            return executor.executeBatchCommand(cwd, JdtlsCommands.FIND_UNREACHABLE_CODE, uris,
                    uri -> Map.of("uri", uri), cancellation, progress);
        }
        return executor.executeCommand(cwd, JdtlsCommands.FIND_UNREACHABLE_CODE,
                Map.of("uri", fileUri),
                cancellation, progress);
    }

    @Tool(name = "java_suggest_imports",
          description = "Find import candidates for an unresolved type name")
    public CompletableFuture<String> suggestImports(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Simple type name to search for (e.g., 'List', 'Map')") String typeName,
            Cancellation cancellation,
            Progress progress) {
        return executor.executeCommand(cwd, JdtlsCommands.SUGGEST_IMPORTS,
                Map.of("typeName", typeName),
                cancellation, progress);
    }

    @Tool(name = "java_get_type_usage_summary",
          description = "Get a comprehensive usage summary for a Java type")
    public CompletableFuture<String> getTypeUsageSummary(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Fully qualified name of the type") String fullyQualifiedName,
            Cancellation cancellation,
            Progress progress) {
        return executor.executeCommand(cwd, JdtlsCommands.GET_TYPE_USAGE_SUMMARY,
                RefactoringHelper.fqnParams(fullyQualifiedName),
                cancellation, progress);
    }

    @Tool(name = "java_search_symbols",
          description = "Search for Java symbols by name pattern (supports glob, e.g. 'My*Service')")
    public CompletableFuture<String> searchSymbols(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Search query string or glob pattern") String query,
            Cancellation cancellation,
            Progress progress) {
        return executor.executeCommand(cwd, JdtlsCommands.SEARCH_SYMBOLS,
                Map.of("query", query),
                cancellation, progress);
    }

    @Tool(name = "java_find_references",
          description = "Find all references to a Java symbol (type, method, or field). Prefer over Grep "
                  + "for Java symbol usages -- resolves through type hierarchy and finds all references semantically.")
    public CompletableFuture<String> findReferences(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.FILE_URI) String fileUri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = JavaToolArgDescriptions.SEARCH_SCOPE, required = false) String scope,
            @ToolArg(description = JavaToolArgDescriptions.PROJECT_NAME, required = false) String projectName,
            @ToolArg(description = JavaToolArgDescriptions.MAX_RESULTS, required = false) Integer maxResults,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(fileUri, line, character);
        RefactoringHelper.putScope(params, scope, projectName);
        RefactoringHelper.putMaxResults(params, maxResults);
        return executor.executeCommand(cwd, JdtlsCommands.FIND_REFERENCES, params, cancellation, progress);
    }

    @Tool(name = "java_find_implementations",
          description = "Find all implementations of a Java interface, abstract class, or method. "
                  + "Provide either fullyQualifiedName (for types) or fileUri+line+character "
                  + "(for types or methods at a cursor position)")
    public CompletableFuture<String> findImplementations(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Fully qualified name of the interface or abstract class",
                     required = false) String fullyQualifiedName,
            @ToolArg(description = ToolArgDescriptions.FILE_URI,
                     required = false) String fileUri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE,
                     required = false) Integer line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER,
                     required = false) Integer character,
            Cancellation cancellation,
            Progress progress) {
        Object params;
        if (fileUri != null && line != null && character != null) {
            params = RefactoringHelper.positionParams(fileUri, line, character);
        } else if (fullyQualifiedName != null) {
            params = RefactoringHelper.fqnParams(fullyQualifiedName);
        } else {
            return CompletableFuture.completedFuture(
                    "Error: provide either fullyQualifiedName or fileUri+line+character");
        }
        return executor.executeCommand(cwd, JdtlsCommands.FIND_IMPLEMENTATIONS,
                params, cancellation, progress);
    }
}
