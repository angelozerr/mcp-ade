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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP tools for advanced Java analysis via JDT.LS delegate command handlers.
 * These tools go beyond standard LSP capabilities by leveraging JDT APIs
 * through delegate command handlers running inside JDT.LS.
 *
 * <p>Tools adapted from <a href="https://github.com/pzalutski-pixel/javalens-mcp">javalens-mcp</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
@ApplicationScoped
public class JavaAnalysisTools {

    @Inject
    JdtlsCommandExecutor executor;

    @Tool(name = "java_get_type_hierarchy",
          description = "Get the full type hierarchy (supertypes, super interfaces, and subtypes) for a Java type")
    public CompletableFuture<String> getTypeHierarchy(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            Cancellation cancellation,
            Progress progress) {

        return executor.executeCommand(cwd, JdtlsCommands.TYPE_HIERARCHY,
                RefactoringHelper.positionParams(uri, line, character),
                cancellation, progress);
    }

    @Tool(name = "java_get_call_hierarchy_incoming",
          description = "Find all callers of a method")
    public CompletableFuture<String> getCallHierarchyIncoming(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = JavaToolArgDescriptions.SEARCH_SCOPE, required = false) String scope,
            @ToolArg(description = JavaToolArgDescriptions.PROJECT_NAME, required = false) String projectName,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putScope(params, scope, projectName);
        return executor.executeCommand(cwd, JdtlsCommands.CALL_HIERARCHY_INCOMING, params, cancellation, progress);
    }

    @Tool(name = "java_get_call_hierarchy_outgoing",
          description = "Find all methods called by a method")
    public CompletableFuture<String> getCallHierarchyOutgoing(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            Cancellation cancellation,
            Progress progress) {

        return executor.executeCommand(cwd, JdtlsCommands.CALL_HIERARCHY_OUTGOING,
                RefactoringHelper.positionParams(uri, line, character),
                cancellation, progress);
    }

    @Tool(name = "java_find_annotation_usages",
          description = "Find all usages of a Java annotation type (@Annotation locations)")
    public CompletableFuture<String> findAnnotationUsages(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Fully qualified name of the annotation (e.g., 'jakarta.inject.Inject')") String fullyQualifiedName,
            @ToolArg(description = JavaToolArgDescriptions.SEARCH_SCOPE, required = false) String scope,
            @ToolArg(description = JavaToolArgDescriptions.PROJECT_NAME, required = false) String projectName,
            @ToolArg(description = JavaToolArgDescriptions.MAX_RESULTS, required = false) Integer maxResults,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.fqnParams(fullyQualifiedName);
        RefactoringHelper.putScope(params, scope, projectName);
        RefactoringHelper.putMaxResults(params, maxResults);
        return executor.executeCommand(cwd, JdtlsCommands.FIND_ANNOTATION_USAGES, params, cancellation, progress);
    }

    @Tool(name = "java_find_type_instantiations",
          description = "Find all 'new Type()' instantiations of a Java type")
    public CompletableFuture<String> findTypeInstantiations(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Fully qualified name of the type (e.g., 'java.util.ArrayList')") String fullyQualifiedName,
            @ToolArg(description = JavaToolArgDescriptions.SEARCH_SCOPE, required = false) String scope,
            @ToolArg(description = JavaToolArgDescriptions.PROJECT_NAME, required = false) String projectName,
            @ToolArg(description = JavaToolArgDescriptions.MAX_RESULTS, required = false) Integer maxResults,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.fqnParams(fullyQualifiedName);
        RefactoringHelper.putScope(params, scope, projectName);
        RefactoringHelper.putMaxResults(params, maxResults);
        return executor.executeCommand(cwd, JdtlsCommands.FIND_TYPE_INSTANTIATIONS, params, cancellation, progress);
    }

    @Tool(name = "java_analyze_file",
          description = "Get comprehensive analysis of a Java file (types, methods, fields, diagnostics, complexity)")
    public CompletableFuture<String> analyzeFile(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = JavaToolArgDescriptions.URIS, required = false) List<String> uris,
            Cancellation cancellation,
            Progress progress) {
        List<String> resolvedUris = RefactoringHelper.resolveUris(uri, uris);
        if (resolvedUris.size() > 1) {
            return executor.executeBatchCommand(cwd, JdtlsCommands.ANALYZE_FILE, resolvedUris,
                    u -> Map.of("uri", u), cancellation, progress);
        }
        return executor.executeCommand(cwd, JdtlsCommands.ANALYZE_FILE,
                Map.of("uri", uri),
                cancellation, progress);
    }

    @Tool(name = "java_analyze_type",
          description = "Get comprehensive analysis of a Java type (hierarchy, members, references, complexity)")
    public CompletableFuture<String> analyzeType(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = "Fully qualified name of the type") String fullyQualifiedName,
            Cancellation cancellation,
            Progress progress) {
        return executor.executeCommand(cwd, JdtlsCommands.ANALYZE_TYPE,
                RefactoringHelper.fqnParams(fullyQualifiedName),
                cancellation, progress);
    }

    @Tool(name = "java_analyze_method",
          description = "Get comprehensive analysis of a Java method (complexity, callers, callees, overrides)")
    public CompletableFuture<String> analyzeMethod(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            Cancellation cancellation,
            Progress progress) {
        return executor.executeCommand(cwd, JdtlsCommands.ANALYZE_METHOD,
                RefactoringHelper.positionParams(uri, line, character),
                cancellation, progress);
    }

    @Tool(name = "java_analyze_change_impact",
          description = "Analyze the ripple effect of changing a symbol")
    public CompletableFuture<String> analyzeChangeImpact(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = JavaToolArgDescriptions.MAX_RESULTS, required = false) Integer maxResults,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putMaxResults(params, maxResults);
        return executor.executeCommand(cwd, JdtlsCommands.ANALYZE_CHANGE_IMPACT, params, cancellation, progress);
    }

}
