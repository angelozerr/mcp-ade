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

/**
 * JDT.LS delegate command IDs for MCP tools.
 */
public final class JdtlsCommands {

    private JdtlsCommands() {
    }

    // --- Diagnostics ---
    public static final String DIAGNOSTICS = "mcp.jdtls.diagnostics";

    // --- Type & call hierarchy ---
    public static final String TYPE_HIERARCHY = "mcp.jdtls.typeHierarchy";
    public static final String CALL_HIERARCHY_INCOMING = "mcp.jdtls.callHierarchyIncoming";
    public static final String CALL_HIERARCHY_OUTGOING = "mcp.jdtls.callHierarchyOutgoing";

    // --- Search ---
    public static final String FIND_ANNOTATION_USAGES = "mcp.jdtls.findAnnotationUsages";
    public static final String FIND_TYPE_INSTANTIATIONS = "mcp.jdtls.findTypeInstantiations";
    public static final String FIND_CASTS = "mcp.jdtls.findCasts";
    public static final String FIND_CATCH_BLOCKS = "mcp.jdtls.findCatchBlocks";
    public static final String FIND_INSTANCEOF_CHECKS = "mcp.jdtls.findInstanceofChecks";
    public static final String FIND_THROWS_DECLARATIONS = "mcp.jdtls.findThrowsDeclarations";
    public static final String FIND_TYPE_ARGUMENTS = "mcp.jdtls.findTypeArguments";
    public static final String FIND_METHOD_REFERENCES = "mcp.jdtls.findMethodReferences";
    public static final String FIND_FIELD_WRITES = "mcp.jdtls.findFieldWrites";
    public static final String FIND_TESTS = "mcp.jdtls.findTests";
    public static final String FIND_AFFECTED_TESTS = "mcp.jdtls.findAffectedTests";
    public static final String FIND_UNUSED_CODE = "mcp.jdtls.findUnusedCode";
    public static final String FIND_UNREACHABLE_CODE = "mcp.jdtls.findUnreachableCode";
    public static final String FIND_REFLECTION_USAGE = "mcp.jdtls.findReflectionUsage";
    public static final String FIND_REFERENCES = "mcp.jdtls.findReferences";
    public static final String FIND_IMPLEMENTATIONS = "mcp.jdtls.findImplementations";
    public static final String SUGGEST_IMPORTS = "mcp.jdtls.suggestImports";
    public static final String GET_TYPE_USAGE_SUMMARY = "mcp.jdtls.getTypeUsageSummary";
    public static final String SEARCH_SYMBOLS = "mcp.jdtls.searchSymbols";

    // --- Metrics ---
    public static final String GET_COMPLEXITY_METRICS = "mcp.jdtls.getComplexityMetrics";

    // --- Navigation ---
    public static final String GO_TO_DEFINITION = "mcp.jdtls.goToDefinition";
    public static final String GET_HOVER_INFO = "mcp.jdtls.getHoverInfo";
    public static final String GET_JAVADOC = "mcp.jdtls.getJavadoc";
    public static final String GET_SYMBOL_INFO = "mcp.jdtls.getSymbolInfo";
    public static final String GET_ENCLOSING_ELEMENT = "mcp.jdtls.getEnclosingElement";
    public static final String GET_FIELD_AT_POSITION = "mcp.jdtls.getFieldAtPosition";
    public static final String GET_METHOD_AT_POSITION = "mcp.jdtls.getMethodAtPosition";
    public static final String GET_TYPE_AT_POSITION = "mcp.jdtls.getTypeAtPosition";
    public static final String GET_SIGNATURE_HELP = "mcp.jdtls.getSignatureHelp";
    public static final String GET_SUPER_METHOD = "mcp.jdtls.getSuperMethod";
    public static final String GET_DOCUMENT_SYMBOLS = "mcp.jdtls.getDocumentSymbols";
    public static final String GET_TYPE_MEMBERS = "mcp.jdtls.getTypeMembers";

    // --- Analysis ---
    public static final String GET_DEPENDENCY_GRAPH = "mcp.jdtls.getDependencyGraph";
    public static final String ANALYZE_CHANGE_IMPACT = "mcp.jdtls.analyzeChangeImpact";
    public static final String ANALYZE_CONTROL_FLOW = "mcp.jdtls.analyzeControlFlow";
    public static final String ANALYZE_DATA_FLOW = "mcp.jdtls.analyzeDataFlow";
    public static final String ANALYZE_FILE = "mcp.jdtls.analyzeFile";
    public static final String ANALYZE_METHOD = "mcp.jdtls.analyzeMethod";
    public static final String ANALYZE_TYPE = "mcp.jdtls.analyzeType";

    // --- Code quality ---
    public static final String FIND_LARGE_CLASSES = "mcp.jdtls.findLargeClasses";
    public static final String FIND_NAMING_VIOLATIONS = "mcp.jdtls.findNamingViolations";
    public static final String FIND_POSSIBLE_BUGS = "mcp.jdtls.findPossibleBugs";
    public static final String FIND_CIRCULAR_DEPENDENCIES = "mcp.jdtls.findCircularDependencies";

    // --- Diagnostics & quick fixes ---
    public static final String VALIDATE_SYNTAX = "mcp.jdtls.validateSyntax";
    public static final String GET_QUICK_FIXES = "mcp.jdtls.getQuickFixes";
    public static final String APPLY_QUICK_FIX = "mcp.jdtls.applyQuickFix";
    public static final String DIAGNOSE_AND_FIX = "mcp.jdtls.diagnoseAndFix";
    public static final String APPLY_CLEANUP = "mcp.jdtls.applyCleanup";

    // --- Framework ---
    public static final String GET_HTTP_ENDPOINTS = "mcp.jdtls.getHttpEndpoints";
    public static final String GET_JPA_MODEL = "mcp.jdtls.getJpaModel";
    public static final String GET_DI_REGISTRATIONS = "mcp.jdtls.getDiRegistrations";

    // --- Refactoring ---
    public static final String RENAME_SYMBOL = "mcp.jdtls.renameSymbol";
    public static final String ORGANIZE_IMPORTS = "mcp.jdtls.organizeImports";
    public static final String EXTRACT_METHOD = "mcp.jdtls.extractMethod";
    public static final String EXTRACT_VARIABLE = "mcp.jdtls.extractVariable";
    public static final String EXTRACT_CONSTANT = "mcp.jdtls.extractConstant";
    public static final String EXTRACT_INTERFACE = "mcp.jdtls.extractInterface";
    public static final String EXTRACT_SUPERCLASS = "mcp.jdtls.extractSuperclass";
    public static final String INLINE_METHOD = "mcp.jdtls.inlineMethod";
    public static final String INLINE_VARIABLE = "mcp.jdtls.inlineVariable";
    public static final String CHANGE_METHOD_SIGNATURE = "mcp.jdtls.changeMethodSignature";
    public static final String CONVERT_ANONYMOUS_TO_LAMBDA = "mcp.jdtls.convertAnonymousToLambda";
    public static final String ENCAPSULATE_FIELD = "mcp.jdtls.encapsulateField";
    public static final String INTRODUCE_PARAMETER_OBJECT = "mcp.jdtls.introduceParameterObject";
    public static final String MOVE_TYPE_TO_NEW_FILE = "mcp.jdtls.moveTypeToNewFile";
    public static final String PULL_UP = "mcp.jdtls.pullUp";
    public static final String PUSH_DOWN = "mcp.jdtls.pushDown";
    public static final String CONVERT_TO_RECORD = "mcp.jdtls.convertToRecord";
    public static final String MOVE_TYPE_TO_PACKAGE = "mcp.jdtls.moveTypeToPackage";

    // --- Code generation ---
    public static final String GENERATE_GETTERS_SETTERS = "mcp.jdtls.generateGettersSetters";
    public static final String GENERATE_CONSTRUCTOR = "mcp.jdtls.generateConstructor";
    public static final String GENERATE_TO_STRING = "mcp.jdtls.generateToString";
    public static final String GENERATE_EQUALS_HASHCODE = "mcp.jdtls.generateEqualsHashCode";
    public static final String GENERATE_DELEGATE_METHODS = "mcp.jdtls.generateDelegateMethods";

    // --- Project ---
    public static final String GET_PROJECT_STRUCTURE = "mcp.jdtls.getProjectStructure";
    public static final String GET_CLASSPATH_INFO = "mcp.jdtls.getClasspathInfo";
    public static final String SETUP_PROJECT = "mcp.jdtls.setupProject";
    public static final String GET_INDEXING_STATUS = "mcp.jdtls.getIndexingStatus";
    public static final String REFRESH_PROJECT = "mcp.jdtls.refreshProject";
}
