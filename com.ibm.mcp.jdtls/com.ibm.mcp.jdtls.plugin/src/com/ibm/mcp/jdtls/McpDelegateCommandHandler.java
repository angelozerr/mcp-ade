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
package com.ibm.mcp.jdtls;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.ls.core.internal.IDelegateCommandHandler;

import com.ibm.mcp.jdtls.handlers.CallHierarchyHandler;
import com.ibm.mcp.jdtls.handlers.DiagnosticsHandler;
import com.ibm.mcp.jdtls.handlers.FindAnnotationUsagesHandler;
import com.ibm.mcp.jdtls.handlers.FindTypeInstantiationsHandler;
import com.ibm.mcp.jdtls.handlers.GetComplexityMetricsHandler;
import com.ibm.mcp.jdtls.handlers.TypeHierarchyHandler;
import com.ibm.mcp.jdtls.handlers.analysis.*;
import com.ibm.mcp.jdtls.handlers.codegen.*;
import com.ibm.mcp.jdtls.handlers.diagnostics.*;
import com.ibm.mcp.jdtls.handlers.framework.*;
import com.ibm.mcp.jdtls.handlers.navigation.*;
import com.ibm.mcp.jdtls.handlers.project.GetClasspathInfoHandler;
import com.ibm.mcp.jdtls.handlers.project.GetIndexingStatusHandler;
import com.ibm.mcp.jdtls.handlers.project.GetProjectStructureHandler;
import com.ibm.mcp.jdtls.handlers.project.SetupProjectHandler;
import com.ibm.mcp.jdtls.handlers.quality.*;
import com.ibm.mcp.jdtls.handlers.refactoring.*;
import com.ibm.mcp.jdtls.handlers.search.*;

/**
 * Main delegate command handler for MCP JDT.LS extensions.
 * Dispatches incoming commands to specialized handlers.
 */
public class McpDelegateCommandHandler implements IDelegateCommandHandler {

    private static final Map<String, ICommandHandler> HANDLERS = new HashMap<>();

    static {
        // --- Existing handlers ---
        HANDLERS.put(JdtlsCommands.DIAGNOSTICS, new DiagnosticsHandler());
        HANDLERS.put(JdtlsCommands.TYPE_HIERARCHY, new TypeHierarchyHandler());
        HANDLERS.put(JdtlsCommands.CALL_HIERARCHY_INCOMING, new CallHierarchyHandler(true));
        HANDLERS.put(JdtlsCommands.CALL_HIERARCHY_OUTGOING, new CallHierarchyHandler(false));
        HANDLERS.put(JdtlsCommands.FIND_ANNOTATION_USAGES, new FindAnnotationUsagesHandler());
        HANDLERS.put(JdtlsCommands.FIND_TYPE_INSTANTIATIONS, new FindTypeInstantiationsHandler());
        HANDLERS.put(JdtlsCommands.GET_COMPLEXITY_METRICS, new GetComplexityMetricsHandler());

        // --- Fine-grained reference search ---
        HANDLERS.put(JdtlsCommands.FIND_CASTS, new FindCastsHandler());
        HANDLERS.put(JdtlsCommands.FIND_CATCH_BLOCKS, new FindCatchBlocksHandler());
        HANDLERS.put(JdtlsCommands.FIND_INSTANCEOF_CHECKS, new FindInstanceofChecksHandler());
        HANDLERS.put(JdtlsCommands.FIND_THROWS_DECLARATIONS, new FindThrowsDeclarationsHandler());
        HANDLERS.put(JdtlsCommands.FIND_TYPE_ARGUMENTS, new FindTypeArgumentsHandler());
        HANDLERS.put(JdtlsCommands.FIND_METHOD_REFERENCES, new FindMethodReferencesHandler());

        // --- Code search ---
        HANDLERS.put(JdtlsCommands.FIND_FIELD_WRITES, new FindFieldWritesHandler());
        HANDLERS.put(JdtlsCommands.FIND_TESTS, new FindTestsHandler());
        HANDLERS.put(JdtlsCommands.FIND_AFFECTED_TESTS, new FindAffectedTestsHandler());
        HANDLERS.put(JdtlsCommands.FIND_UNUSED_CODE, new FindUnusedCodeHandler());
        HANDLERS.put(JdtlsCommands.FIND_UNREACHABLE_CODE, new FindUnreachableCodeHandler());
        HANDLERS.put(JdtlsCommands.FIND_REFLECTION_USAGE, new FindReflectionUsageHandler());
        HANDLERS.put(JdtlsCommands.SUGGEST_IMPORTS, new SuggestImportsHandler());
        HANDLERS.put(JdtlsCommands.GET_TYPE_USAGE_SUMMARY, new GetTypeUsageSummaryHandler());
        HANDLERS.put(JdtlsCommands.SEARCH_SYMBOLS, new SearchSymbolsHandler());
        HANDLERS.put(JdtlsCommands.FIND_REFERENCES, new FindReferencesHandler());
        HANDLERS.put(JdtlsCommands.FIND_IMPLEMENTATIONS, new FindImplementationsHandler());

        // --- Navigation ---
        HANDLERS.put(JdtlsCommands.GO_TO_DEFINITION, new GoToDefinitionHandler());
        HANDLERS.put(JdtlsCommands.GET_HOVER_INFO, new GetHoverInfoHandler());
        HANDLERS.put(JdtlsCommands.GET_JAVADOC, new GetJavadocHandler());
        HANDLERS.put(JdtlsCommands.GET_SYMBOL_INFO, new GetSymbolInfoHandler());
        HANDLERS.put(JdtlsCommands.GET_ENCLOSING_ELEMENT, new GetEnclosingElementHandler());
        HANDLERS.put(JdtlsCommands.GET_FIELD_AT_POSITION, new GetFieldAtPositionHandler());
        HANDLERS.put(JdtlsCommands.GET_METHOD_AT_POSITION, new GetMethodAtPositionHandler());
        HANDLERS.put(JdtlsCommands.GET_TYPE_AT_POSITION, new GetTypeAtPositionHandler());
        HANDLERS.put(JdtlsCommands.GET_SIGNATURE_HELP, new GetSignatureHelpHandler());
        HANDLERS.put(JdtlsCommands.GET_SUPER_METHOD, new GetSuperMethodHandler());
        HANDLERS.put(JdtlsCommands.GET_DOCUMENT_SYMBOLS, new GetDocumentSymbolsHandler());
        HANDLERS.put(JdtlsCommands.GET_TYPE_MEMBERS, new GetTypeMembersHandler());

        // --- Analysis ---
        HANDLERS.put(JdtlsCommands.GET_DEPENDENCY_GRAPH, new GetDependencyGraphHandler());
        HANDLERS.put(JdtlsCommands.ANALYZE_CHANGE_IMPACT, new AnalyzeChangeImpactHandler());
        HANDLERS.put(JdtlsCommands.ANALYZE_CONTROL_FLOW, new AnalyzeControlFlowHandler());
        HANDLERS.put(JdtlsCommands.ANALYZE_DATA_FLOW, new AnalyzeDataFlowHandler());
        HANDLERS.put(JdtlsCommands.ANALYZE_FILE, new AnalyzeFileHandler());
        HANDLERS.put(JdtlsCommands.ANALYZE_METHOD, new AnalyzeMethodHandler());
        HANDLERS.put(JdtlsCommands.ANALYZE_TYPE, new AnalyzeTypeHandler());

        // --- Code quality ---
        HANDLERS.put(JdtlsCommands.FIND_LARGE_CLASSES, new FindLargeClassesHandler());
        HANDLERS.put(JdtlsCommands.FIND_NAMING_VIOLATIONS, new FindNamingViolationsHandler());
        HANDLERS.put(JdtlsCommands.FIND_POSSIBLE_BUGS, new FindPossibleBugsHandler());
        HANDLERS.put(JdtlsCommands.FIND_CIRCULAR_DEPENDENCIES, new FindCircularDependenciesHandler());

        // --- Diagnostics & quick fixes ---
        HANDLERS.put(JdtlsCommands.VALIDATE_SYNTAX, new ValidateSyntaxHandler());
        HANDLERS.put(JdtlsCommands.GET_QUICK_FIXES, new GetQuickFixesHandler());
        HANDLERS.put(JdtlsCommands.APPLY_QUICK_FIX, new ApplyQuickFixHandler());
        HANDLERS.put(JdtlsCommands.DIAGNOSE_AND_FIX, new DiagnoseAndFixHandler());
        HANDLERS.put(JdtlsCommands.APPLY_CLEANUP, new ApplyCleanupHandler());

        // --- Framework-specific ---
        HANDLERS.put(JdtlsCommands.GET_HTTP_ENDPOINTS, new GetHttpEndpointsHandler());
        HANDLERS.put(JdtlsCommands.GET_JPA_MODEL, new GetJpaModelHandler());
        HANDLERS.put(JdtlsCommands.GET_DI_REGISTRATIONS, new GetDiRegistrationsHandler());

        // --- Refactoring ---
        HANDLERS.put(JdtlsCommands.RENAME_SYMBOL, new RenameSymbolHandler());
        HANDLERS.put(JdtlsCommands.ORGANIZE_IMPORTS, new OrganizeImportsHandler());
        HANDLERS.put(JdtlsCommands.EXTRACT_METHOD, new ExtractMethodHandler());
        HANDLERS.put(JdtlsCommands.EXTRACT_VARIABLE, new ExtractVariableHandler());
        HANDLERS.put(JdtlsCommands.EXTRACT_CONSTANT, new ExtractConstantHandler());
        HANDLERS.put(JdtlsCommands.EXTRACT_INTERFACE, new ExtractInterfaceHandler());
        HANDLERS.put(JdtlsCommands.EXTRACT_SUPERCLASS, new ExtractSuperclassHandler());
        HANDLERS.put(JdtlsCommands.INLINE_METHOD, new InlineMethodHandler());
        HANDLERS.put(JdtlsCommands.INLINE_VARIABLE, new InlineVariableHandler());
        HANDLERS.put(JdtlsCommands.CHANGE_METHOD_SIGNATURE, new ChangeMethodSignatureHandler());
        HANDLERS.put(JdtlsCommands.CONVERT_ANONYMOUS_TO_LAMBDA, new ConvertAnonymousToLambdaHandler());
        HANDLERS.put(JdtlsCommands.ENCAPSULATE_FIELD, new EncapsulateFieldHandler());
        HANDLERS.put(JdtlsCommands.INTRODUCE_PARAMETER_OBJECT, new IntroduceParameterObjectHandler());
        HANDLERS.put(JdtlsCommands.MOVE_TYPE_TO_NEW_FILE, new MoveTypeToNewFileHandler());
        HANDLERS.put(JdtlsCommands.PULL_UP, new PullUpHandler());
        HANDLERS.put(JdtlsCommands.PUSH_DOWN, new PushDownHandler());
        HANDLERS.put(JdtlsCommands.CONVERT_TO_RECORD, new ConvertToRecordHandler());
        HANDLERS.put(JdtlsCommands.MOVE_TYPE_TO_PACKAGE, new MoveTypeToPackageHandler());

        // --- Code generation ---
        HANDLERS.put(JdtlsCommands.GENERATE_GETTERS_SETTERS, new GenerateGettersSettersHandler());
        HANDLERS.put(JdtlsCommands.GENERATE_CONSTRUCTOR, new GenerateConstructorHandler());
        HANDLERS.put(JdtlsCommands.GENERATE_TO_STRING, new GenerateToStringHandler());
        HANDLERS.put(JdtlsCommands.GENERATE_EQUALS_HASHCODE, new GenerateEqualsHashCodeHandler());
        HANDLERS.put(JdtlsCommands.GENERATE_DELEGATE_METHODS, new GenerateDelegateMethodsHandler());

        // --- Project ---
        HANDLERS.put(JdtlsCommands.GET_PROJECT_STRUCTURE, new GetProjectStructureHandler());
        HANDLERS.put(JdtlsCommands.GET_CLASSPATH_INFO, new GetClasspathInfoHandler());
        HANDLERS.put(JdtlsCommands.SETUP_PROJECT, new SetupProjectHandler());
        HANDLERS.put(JdtlsCommands.GET_INDEXING_STATUS, new GetIndexingStatusHandler());
    }

    @Override
    public Object executeCommand(String commandId, List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (monitor == null) {
            monitor = new NullProgressMonitor();
        }
        ICommandHandler handler = HANDLERS.get(commandId);
        if (handler == null) {
            throw new UnsupportedOperationException("Unknown command: " + commandId);
        }
        return handler.execute(arguments, monitor);
    }
}
