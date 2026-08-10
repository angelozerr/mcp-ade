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
package com.ibm.mcp.jdtls.tests;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.mcp.jdtls.handlers.CallHierarchyHandler;
import com.ibm.mcp.jdtls.handlers.DiagnosticsHandler;
import com.ibm.mcp.jdtls.handlers.FindAnnotationUsagesHandler;
import com.ibm.mcp.jdtls.handlers.FindTypeInstantiationsHandler;
import com.ibm.mcp.jdtls.handlers.GetComplexityMetricsHandler;
import com.ibm.mcp.jdtls.handlers.TypeHierarchyHandler;
import com.ibm.mcp.jdtls.handlers.analysis.AnalyzeChangeImpactHandler;
import com.ibm.mcp.jdtls.handlers.analysis.AnalyzeControlFlowHandler;
import com.ibm.mcp.jdtls.handlers.analysis.AnalyzeDataFlowHandler;
import com.ibm.mcp.jdtls.handlers.analysis.AnalyzeFileHandler;
import com.ibm.mcp.jdtls.handlers.analysis.AnalyzeMethodHandler;
import com.ibm.mcp.jdtls.handlers.analysis.AnalyzeTypeHandler;
import com.ibm.mcp.jdtls.handlers.analysis.GetDependencyGraphHandler;
import com.ibm.mcp.jdtls.handlers.codegen.GenerateConstructorHandler;
import com.ibm.mcp.jdtls.handlers.codegen.GenerateDelegateMethodsHandler;
import com.ibm.mcp.jdtls.handlers.codegen.GenerateEqualsHashCodeHandler;
import com.ibm.mcp.jdtls.handlers.codegen.GenerateGettersSettersHandler;
import com.ibm.mcp.jdtls.handlers.codegen.GenerateToStringHandler;
import com.ibm.mcp.jdtls.handlers.diagnostics.ApplyCleanupHandler;
import com.ibm.mcp.jdtls.handlers.diagnostics.ApplyQuickFixHandler;
import com.ibm.mcp.jdtls.handlers.diagnostics.DiagnoseAndFixHandler;
import com.ibm.mcp.jdtls.handlers.diagnostics.GetQuickFixesHandler;
import com.ibm.mcp.jdtls.handlers.diagnostics.ValidateSyntaxHandler;
import com.ibm.mcp.jdtls.handlers.framework.GetDiRegistrationsHandler;
import com.ibm.mcp.jdtls.handlers.framework.GetHttpEndpointsHandler;
import com.ibm.mcp.jdtls.handlers.framework.GetJpaModelHandler;
import com.ibm.mcp.jdtls.handlers.project.GetClasspathInfoHandler;
import com.ibm.mcp.jdtls.handlers.project.GetProjectStructureHandler;
import com.ibm.mcp.jdtls.handlers.quality.FindCircularDependenciesHandler;
import com.ibm.mcp.jdtls.handlers.quality.FindLargeClassesHandler;
import com.ibm.mcp.jdtls.handlers.quality.FindNamingViolationsHandler;
import com.ibm.mcp.jdtls.handlers.quality.FindPossibleBugsHandler;
import com.ibm.mcp.jdtls.handlers.search.FindAffectedTestsHandler;
import com.ibm.mcp.jdtls.handlers.search.FindCastsHandler;
import com.ibm.mcp.jdtls.handlers.search.FindCatchBlocksHandler;
import com.ibm.mcp.jdtls.handlers.search.FindFieldWritesHandler;
import com.ibm.mcp.jdtls.handlers.search.FindImplementationsHandler;
import com.ibm.mcp.jdtls.handlers.search.FindInstanceofChecksHandler;
import com.ibm.mcp.jdtls.handlers.search.FindMethodReferencesHandler;
import com.ibm.mcp.jdtls.handlers.search.FindReferencesHandler;
import com.ibm.mcp.jdtls.handlers.search.FindReflectionUsageHandler;
import com.ibm.mcp.jdtls.handlers.search.FindTestsHandler;
import com.ibm.mcp.jdtls.handlers.search.FindThrowsDeclarationsHandler;
import com.ibm.mcp.jdtls.handlers.search.FindTypeArgumentsHandler;
import com.ibm.mcp.jdtls.handlers.search.FindUnreachableCodeHandler;
import com.ibm.mcp.jdtls.handlers.search.FindUnusedCodeHandler;
import com.ibm.mcp.jdtls.handlers.search.GetTypeUsageSummaryHandler;
import com.ibm.mcp.jdtls.handlers.search.SearchSymbolsHandler;
import com.ibm.mcp.jdtls.handlers.search.SuggestImportsHandler;

/**
 * Comprehensive JUnit 5 tests for Eclipse JDT analysis, search, quality,
 * diagnostics, code generation, project, framework, and root-package handlers.
 *
 * <p>Test project "simple-java" layout (0-based line numbers):</p>
 * <ul>
 *   <li>src/com/example/model/User.java - class (line 13), fields name/age/email/roles,
 *       constructor, getters/setters, isAdult(), getDisplayName(), toString()</li>
 *   <li>src/com/example/model/Admin.java - extends User, getDisplayName() override (line 31)</li>
 *   <li>src/com/example/service/UserService.java - addUser, findByName, findAdults,
 *       searchUsers (line 76), processUsers (line 98). MAX_USERS constant (line 15).
 *       Has IOException throws on loadUsersFromFile.</li>
 *   <li>src/com/example/service/Validator.java - generic interface Validator&lt;T&gt;</li>
 *   <li>src/com/example/service/UserValidator.java - implements Validator&lt;User&gt;</li>
 *   <li>src/com/example/util/StringUtils.java - static methods, EMPTY constant (line 4)</li>
 *   <li>src/com/example/controller/UserController.java - uses UserService, UserValidator, StringUtils</li>
 *   <li>test/com/example/UserServiceTest.java - simple test class (no JUnit annotations)</li>
 * </ul>
 */
public class AnalysisAndSearchHandlerTest extends AbstractHandlerTest {

    // =========================================================================
    // Analysis handlers
    // =========================================================================

    @Test
    void testAnalyzeFileHandler() throws Exception {
        AnalyzeFileHandler handler = new AnalyzeFileHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals(uri, map.get("uri"));

        // User.java has 1 type
        assertTrue(((Number) map.get("types")).intValue() >= 1,
                "Should find at least 1 type");

        // Should have methods
        assertNotNull(map.get("methods"));
        assertInstanceOf(List.class, map.get("methods"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) map.get("methods");
        assertTrue(methods.size() >= 5, "User.java should have several methods");

        // Should have fields (name, age, email, roles)
        assertTrue(((Number) map.get("fields")).intValue() >= 4,
                "User.java should have at least 4 fields");

        // Should have LOC
        assertTrue(((Number) map.get("loc")).intValue() > 0, "LOC should be positive");
    }

    @Test
    void testAnalyzeMethodHandler() throws Exception {
        AnalyzeMethodHandler handler = new AnalyzeMethodHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        // searchUsers is at line 76 (0-based), character 25
        Map<String, Object> p = params(uri, 76, 25);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        // The result should contain method analysis info
        assertNotNull(map);
        // Should not contain an error
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testAnalyzeTypeHandler() throws Exception {
        AnalyzeTypeHandler handler = new AnalyzeTypeHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("com.example.model.User", map.get("type"));
        assertEquals("class", map.get("kind"));

        // User has at least 1 subtype (Admin)
        assertTrue(((Number) map.get("subtypes")).intValue() >= 1,
                "User should have at least 1 subtype (Admin)");

        // User has methods
        assertTrue(((Number) map.get("methods")).intValue() >= 5,
                "User should have several methods");

        // User has fields
        assertTrue(((Number) map.get("fields")).intValue() >= 4,
                "User should have at least 4 fields");

        // Should have references in the project
        assertTrue(((Number) map.get("references")).intValue() >= 1,
                "User should be referenced in other files");

        // Should have members list
        assertNotNull(map.get("members"));
        assertInstanceOf(List.class, map.get("members"));
    }

    @Test
    void testAnalyzeControlFlowHandler() throws Exception {
        AnalyzeControlFlowHandler handler = new AnalyzeControlFlowHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        // processUsers() at line 98 (0-based), char 20
        Map<String, Object> p = params(uri, 98, 20);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // processUsers has a for loop, if statements - should report branches/loops
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testAnalyzeDataFlowHandler() throws Exception {
        AnalyzeDataFlowHandler handler = new AnalyzeDataFlowHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        // findByName method at line 36 (0-based), character 26
        Map<String, Object> p = params(uri, 36, 26);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testAnalyzeChangeImpactHandler() throws Exception {
        AnalyzeChangeImpactHandler handler = new AnalyzeChangeImpactHandler();
        String uri = fileUri("src/com/example/model/User.java");

        // getName() at line 33 (0-based), character 18
        Map<String, Object> p = params(uri, 33, 18);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testGetComplexityMetricsHandler() throws Exception {
        GetComplexityMetricsHandler handler = new GetComplexityMetricsHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals(uri, map.get("uri"));

        // UserService has multiple methods
        assertNotNull(map.get("methods"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) map.get("methods");
        assertTrue(methods.size() >= 5, "UserService should have several methods");

        // processUsers should have higher complexity due to nested if/for
        boolean foundProcessUsers = false;
        for (Map<String, Object> method : methods) {
            if ("processUsers".equals(method.get("name"))) {
                foundProcessUsers = true;
                int complexity = ((Number) method.get("cyclomaticComplexity")).intValue();
                assertTrue(complexity > 1, "processUsers should have complexity > 1");
            }
        }
        assertTrue(foundProcessUsers, "Should find processUsers method");
    }

    @Test
    void testGetDependencyGraphHandler() throws Exception {
        GetDependencyGraphHandler handler = new GetDependencyGraphHandler();

        // Workspace-wide (no uri param)
        Map<String, Object> p = new HashMap<>();
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        // Should have nodes (packages)
        assertNotNull(map.get("nodes"));
        @SuppressWarnings("unchecked")
        List<String> nodes = (List<String>) map.get("nodes");
        assertTrue(nodes.size() >= 2, "Should find multiple packages");

        // Should have edges (dependencies between packages)
        assertNotNull(map.get("edges"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) map.get("edges");
        assertTrue(edges.size() >= 1, "Should find at least 1 dependency edge");

        assertTrue(((Number) map.get("nodeCount")).intValue() >= 2);
        assertTrue(((Number) map.get("edgeCount")).intValue() >= 1);
    }

    // =========================================================================
    // Search handlers
    // =========================================================================

    @Test
    void testFindReferencesHandler() throws Exception {
        FindReferencesHandler handler = new FindReferencesHandler();
        String uri = fileUri("src/com/example/model/User.java");

        // User class name at line 12 (0-based), character 15
        Map<String, Object> p = params(uri, 12, 15);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("element"));
        assertNotNull(map.get("references"));
        // User is referenced in many places (UserService, UserController, Admin, etc.)
        assertTrue(((Number) map.get("count")).intValue() >= 1,
                "User should have references in other files");

        // Verify line/character format (not offset) — references are grouped by URI
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> refs = (Map<String, List<Map<String, Object>>>) map.get("references");
        Map.Entry<String, List<Map<String, Object>>> firstEntry = refs.entrySet().iterator().next();
        assertNotNull(firstEntry.getKey(), "Reference group should have uri as key");
        Map<String, Object> firstRef = firstEntry.getValue().get(0);
        assertNotNull(firstRef.get("line"), "Reference should have line");
        assertNotNull(firstRef.get("character"), "Reference should have character");
        assertNull(firstRef.get("offset"), "Reference should NOT have offset");
    }

    @Test
    void testFindMethodReferencesHandler() throws Exception {
        FindMethodReferencesHandler handler = new FindMethodReferencesHandler();
        String uri = fileUri("src/com/example/model/User.java");

        // getName() at line 33 (0-based), character 18
        Map<String, Object> p = params(uri, 33, 18);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // getName() is called in UserService.findByName, UserValidator, Admin, etc.
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindFieldWritesHandler() throws Exception {
        FindFieldWritesHandler handler = new FindFieldWritesHandler();
        String uri = fileUri("src/com/example/model/User.java");

        // name field at line 14 (0-based), character 19
        Map<String, Object> p = params(uri, 14, 19);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // name field is written in constructor and setName
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindImplementationsHandler() throws Exception {
        FindImplementationsHandler handler = new FindImplementationsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.service.Validator");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("com.example.service.Validator", map.get("type"));

        // UserValidator implements Validator
        assertTrue(((Number) map.get("count")).intValue() >= 1,
                "Validator should have at least 1 implementation (UserValidator)");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impls = (List<Map<String, Object>>) map.get("implementations");
        assertTrue(impls.size() >= 1);

        boolean foundUserValidator = impls.stream()
                .anyMatch(impl -> "UserValidator".equals(impl.get("name")));
        assertTrue(foundUserValidator, "Should find UserValidator as implementation");
    }

    @Test
    void testFindTestsHandler() throws Exception {
        FindTestsHandler handler = new FindTestsHandler();

        // Workspace-wide search (no uri)
        Map<String, Object> p = new HashMap<>();
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("count"));
        assertNotNull(map.get("tests"));
        // The test project's UserServiceTest has no JUnit annotations,
        // so the count may be 0, which is fine
        assertTrue(((Number) map.get("count")).intValue() >= 0);
    }

    @Test
    void testFindAffectedTestsHandler_noNPE() throws Exception {
        // Bug #11 regression test: verify no NPE when searching affected tests
        FindAffectedTestsHandler handler = new FindAffectedTestsHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        // addUser method at line 23 (0-based), character 16
        Map<String, Object> p = params(uri, 23, 16);

        // Should not throw NPE
        Object result = assertDoesNotThrow(
                () -> handler.execute(args(p), MONITOR),
                "FindAffectedTestsHandler should not throw NPE (Bug #11 regression)");

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("element"));
        assertNotNull(map.get("count"));
        assertNotNull(map.get("affectedTests"));
    }

    @Test
    void testFindUnusedCodeHandler() throws Exception {
        FindUnusedCodeHandler handler = new FindUnusedCodeHandler();
        String uri = fileUri("src/com/example/util/StringUtils.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindUnreachableCodeHandler() throws Exception {
        FindUnreachableCodeHandler handler = new FindUnreachableCodeHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindReflectionUsageHandler() throws Exception {
        FindReflectionUsageHandler handler = new FindReflectionUsageHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        // No reflection usage in User.java, so expect empty or zero count results
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testSuggestImportsHandler() throws Exception {
        SuggestImportsHandler handler = new SuggestImportsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("typeName", "List");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("List", map.get("typeName"));
        assertTrue(((Number) map.get("count")).intValue() >= 1,
                "Should find at least java.util.List");

        @SuppressWarnings("unchecked")
        List<String> suggestions = (List<String>) map.get("suggestions");
        assertTrue(suggestions.contains("java.util.List"),
                "Suggestions should include java.util.List");
    }

    @Test
    void testSearchSymbolsHandler() throws Exception {
        SearchSymbolsHandler handler = new SearchSymbolsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("query", "User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("User", map.get("query"));
        assertTrue(((Number) map.get("count")).intValue() >= 1,
                "Should find at least the User class");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> symbols = (List<Map<String, Object>>) map.get("symbols");
        assertTrue(symbols.size() >= 1);

        boolean foundUser = symbols.stream()
                .anyMatch(s -> "User".equals(s.get("name")) && "type".equals(s.get("kind")));
        assertTrue(foundUser, "Should find User type in results");
    }

    @Test
    void testGetTypeUsageSummaryHandler() throws Exception {
        GetTypeUsageSummaryHandler handler = new GetTypeUsageSummaryHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // User is used in various ways across the project
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindCastsHandler() throws Exception {
        FindCastsHandler handler = new FindCastsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // May or may not find casts depending on test code, just verify no error
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindCatchBlocksHandler() throws Exception {
        FindCatchBlocksHandler handler = new FindCatchBlocksHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "java.io.IOException");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindInstanceofChecksHandler() throws Exception {
        FindInstanceofChecksHandler handler = new FindInstanceofChecksHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindThrowsDeclarationsHandler() throws Exception {
        FindThrowsDeclarationsHandler handler = new FindThrowsDeclarationsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "java.io.IOException");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // UserService.loadUsersFromFile throws IOException
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindTypeArgumentsHandler() throws Exception {
        FindTypeArgumentsHandler handler = new FindTypeArgumentsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // Validator<User> uses User as type argument
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    // =========================================================================
    // Quality handlers
    // =========================================================================

    @Test
    void testFindLargeClassesHandler() throws Exception {
        FindLargeClassesHandler handler = new FindLargeClassesHandler();

        Map<String, Object> p = new HashMap<>();
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // Test project classes are small, so may not find large classes
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindNamingViolationsHandler() throws Exception {
        FindNamingViolationsHandler handler = new FindNamingViolationsHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindPossibleBugsHandler() throws Exception {
        FindPossibleBugsHandler handler = new FindPossibleBugsHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindCircularDependenciesHandler() throws Exception {
        FindCircularDependenciesHandler handler = new FindCircularDependenciesHandler();

        Map<String, Object> p = new HashMap<>();
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    // =========================================================================
    // Diagnostics handlers
    // =========================================================================

    @Test
    void testValidateSyntaxHandler() throws Exception {
        ValidateSyntaxHandler handler = new ValidateSyntaxHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        // User.java should have valid syntax
        assertEquals(true, map.get("valid"), "User.java should have valid syntax");
    }

    @Test
    void testDiagnoseAndFixHandler() throws Exception {
        DiagnoseAndFixHandler handler = new DiagnoseAndFixHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals(uri, map.get("uri"));
        assertNotNull(map.get("diagnostics"));
        assertNotNull(map.get("totalDiagnostics"));
    }

    @Test
    void testGetQuickFixesHandler() throws Exception {
        // Hard to test without compilation errors, but we verify no crash
        GetQuickFixesHandler handler = new GetQuickFixesHandler();
        String uri = fileUri("src/com/example/model/User.java");

        // Position on a valid symbol - should return empty or minimal fixes
        Map<String, Object> p = params(uri, 33, 18);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        // Result might be a map or have various shapes depending on implementation
    }

    @Test
    void testApplyCleanupHandler_removeUnusedImports() throws Exception {
        ApplyCleanupHandler handler = new ApplyCleanupHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        p.put("cleanupId", "remove_unused_imports");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals(uri, map.get("uri"));
        assertEquals("remove_unused_imports", map.get("cleanupId"));
        assertNotNull(map.get("changesApplied"));
    }

    @Test
    void testApplyQuickFixHandler_unknownFixId() throws Exception {
        ApplyQuickFixHandler handler = new ApplyQuickFixHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = params(uri, 14, 10);
        p.put("fixId", "non_existent_fix");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals(false, map.get("applied"));
        assertTrue(((String) map.get("error")).contains("Unknown fix ID"),
                "Should report unknown fix ID error");
    }

    @Test
    void testApplyQuickFixHandler_missingArguments() throws Exception {
        ApplyQuickFixHandler handler = new ApplyQuickFixHandler();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(List.of(), MONITOR));
        assertTrue(ex.getMessage().contains("Missing arguments"),
                "Should report missing arguments error");
    }

    // =========================================================================
    // Code generation handlers
    // =========================================================================

    @Test
    void testGenerateGettersSettersHandler() throws Exception {
        GenerateGettersSettersHandler handler = new GenerateGettersSettersHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        // Admin class at line 4 (0-based), character 15
        Map<String, Object> p = params(uri, 4, 15);
        // Admin already has all getters/setters, so handler throws
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("already exist"),
                "Should report all getters/setters already exist");
    }

    @Test
    void testGenerateConstructorHandler() throws Exception {
        GenerateConstructorHandler handler = new GenerateConstructorHandler();
        String uri = fileUri("src/com/example/util/StringUtils.java");

        // StringUtils class at line 2 (0-based), character 15
        Map<String, Object> p = params(uri, 2, 15);
        // StringUtils has no eligible fields, so handler throws
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("No constructor to generate"),
                "Should report no constructor to generate");
    }

    @Test
    void testGenerateToStringHandler() throws Exception {
        GenerateToStringHandler handler = new GenerateToStringHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        // Admin class at line 4 (0-based), character 15
        Map<String, Object> p = params(uri, 4, 15);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
    }

    @Test
    void testGenerateEqualsHashCodeHandler() throws Exception {
        GenerateEqualsHashCodeHandler handler = new GenerateEqualsHashCodeHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        // Admin class at line 4 (0-based), character 15
        Map<String, Object> p = params(uri, 4, 15);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
    }

    @Test
    void testGenerateDelegateMethodsHandler() throws Exception {
        GenerateDelegateMethodsHandler handler = new GenerateDelegateMethodsHandler();
        String uri = fileUri("src/com/example/controller/UserController.java");

        // userService field at line 11 (0-based), character 34
        Map<String, Object> p = params(uri, 11, 34);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // UserService has public methods not already in UserController,
        // so delegate methods should be generated
        assertEquals(false, map.get("applied"),
                "Should return preview (applied=false)");
        assertNull(map.get("error"),
                "Should not return error: " + map.get("error"));

        @SuppressWarnings("unchecked")
        List<Object> edits = (List<Object>) map.get("edits");
        assertNotNull(edits, "Edits must not be null");
        assertFalse(edits.isEmpty(), "Should generate delegate method edits");
    }

    @Test
    void testGenerateDelegateMethodsHandler_noFieldAtPosition() throws Exception {
        GenerateDelegateMethodsHandler handler = new GenerateDelegateMethodsHandler();
        String uri = fileUri("src/com/example/controller/UserController.java");

        // Position on class name, not a field (line 9, character 13)
        Map<String, Object> p = params(uri, 9, 13);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("No field found at position"),
                "Should report error when not positioned on a field");
    }

    // =========================================================================
    // Project handlers
    // =========================================================================

    @Test
    void testGetProjectStructureHandler() throws Exception {
        GetProjectStructureHandler handler = new GetProjectStructureHandler();

        Map<String, Object> p = new HashMap<>();
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("project"));
        assertNotNull(map.get("sourceFolders"));
        assertTrue(((Number) map.get("totalPackages")).intValue() >= 1,
                "Should find at least 1 package");
        assertTrue(((Number) map.get("totalFiles")).intValue() >= 1,
                "Should find at least 1 file");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sourceFolders = (List<Map<String, Object>>) map.get("sourceFolders");
        assertTrue(sourceFolders.size() >= 1, "Should have at least 1 source folder");
    }

    @Test
    void testGetProjectStructureHandler_withProjectName() throws Exception {
        GetProjectStructureHandler handler = new GetProjectStructureHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("projectName", "simple-java");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("simple-java", map.get("project"));
    }

    @Test
    void testGetClasspathInfoHandler() throws Exception {
        GetClasspathInfoHandler handler = new GetClasspathInfoHandler();

        Map<String, Object> p = new HashMap<>();
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    // =========================================================================
    // Type/Hierarchy handlers (root package)
    // =========================================================================

    @Test
    void testTypeHierarchyHandler() throws Exception {
        TypeHierarchyHandler handler = new TypeHierarchyHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        // Should have type info
        assertNotNull(map.get("type"));
        Map<String, Object> typeInfo = asMap(map.get("type"));
        assertEquals("User", typeInfo.get("name"));
        assertEquals("com.example.model.User", typeInfo.get("fullyQualifiedName"));

        // Should have supertypes (at least java.lang.Object)
        assertNotNull(map.get("supertypes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> supertypes = (List<Map<String, Object>>) map.get("supertypes");
        assertTrue(supertypes.size() >= 1, "Should have at least Object as supertype");

        // Should have subtypes (Admin)
        assertNotNull(map.get("subtypes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subtypes = (List<Map<String, Object>>) map.get("subtypes");
        assertTrue(subtypes.size() >= 1, "Should have Admin as subtype");

        boolean foundAdmin = subtypes.stream()
                .anyMatch(t -> "Admin".equals(t.get("name")));
        assertTrue(foundAdmin, "Should find Admin in subtypes");
    }

    @Test
    void testCallHierarchyHandler_incoming() throws Exception {
        CallHierarchyHandler handler = new CallHierarchyHandler(true);
        String uri = fileUri("src/com/example/service/UserService.java");

        // addUser method at line 23 (0-based), character 16
        Map<String, Object> p = params(uri, 23, 16);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("addUser", map.get("method"));
        assertNotNull(map.get("callers"));

        // addUser is called from UserController.createUser
        @SuppressWarnings("unchecked")
        Map<String, ?> callers = (Map<String, ?>) map.get("callers");
        assertTrue(callers.size() >= 1,
                "addUser should be called from at least UserController");
    }

    @Test
    void testCallHierarchyHandler_outgoing() throws Exception {
        CallHierarchyHandler handler = new CallHierarchyHandler(false);
        String uri = fileUri("src/com/example/service/UserService.java");

        // processUsers method at line 98 (0-based), character 16
        Map<String, Object> p = params(uri, 98, 16);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("processUsers", map.get("method"));
        assertNotNull(map.get("callees"));

        // processUsers calls isAdult(), getRoles(), addRole(), getName()
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callees = (List<Map<String, Object>>) map.get("callees");
        assertTrue(callees.size() >= 1,
                "processUsers should call other methods");
    }

    @Test
    void testDiagnosticsHandler() throws Exception {
        DiagnosticsHandler handler = new DiagnosticsHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uris", List.of(uri));
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        assertInstanceOf(List.class, result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnosticsList = (List<Map<String, Object>>) result;
        assertTrue(diagnosticsList.size() >= 1, "Should return diagnostics for at least 1 file");

        Map<String, Object> fileDiag = diagnosticsList.get(0);
        assertEquals(uri, fileDiag.get("uri"));
        assertNotNull(fileDiag.get("diagnostics"));
    }

    @Test
    void testFindAnnotationUsagesHandler() throws Exception {
        FindAnnotationUsagesHandler handler = new FindAnnotationUsagesHandler();

        // Override is used as annotation in the test project
        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "java.lang.Override");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // @Override is used in User.toString(), Admin.getDisplayName(), UserValidator methods
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindTypeInstantiationsHandler() throws Exception {
        FindTypeInstantiationsHandler handler = new FindTypeInstantiationsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("com.example.model.User", map.get("type"));

        // User is instantiated in UserController.createUser and UserServiceTest
        assertTrue(((Number) map.get("count")).intValue() >= 1,
                "User should be instantiated at least once");

        @SuppressWarnings("unchecked")
        Map<String, ?> instantiations = (Map<String, ?>) map.get("instantiations");
        assertTrue(instantiations.size() >= 1);
    }

    // =========================================================================
    // Framework handlers
    // =========================================================================

    @Test
    void testGetHttpEndpointsHandler() throws Exception {
        GetHttpEndpointsHandler handler = new GetHttpEndpointsHandler();

        Map<String, Object> p = new HashMap<>();
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // Test project has no Spring/JAX-RS annotations, expect empty results
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testGetJpaModelHandler() throws Exception {
        GetJpaModelHandler handler = new GetJpaModelHandler();

        Map<String, Object> p = new HashMap<>();
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map);
        // Test project has no JPA annotations, expect empty results
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testGetDiRegistrationsHandler() throws Exception {
        GetDiRegistrationsHandler handler = new GetDiRegistrationsHandler();

        Map<String, Object> p = new HashMap<>();
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("registrations"));
        assertNotNull(map.get("injectionPoints"));
        assertNotNull(map.get("counts"));

        // Test project has no DI annotations, expect empty
        Map<String, Object> counts = asMap(map.get("counts"));
        assertEquals(0, ((Number) counts.get("registrations")).intValue(),
                "No DI registrations expected in test project");
        assertEquals(0, ((Number) counts.get("injectionPoints")).intValue(),
                "No injection points expected in test project");
    }

    @Test
    void testGetDiRegistrationsHandler_withProjectScope() throws Exception {
        GetDiRegistrationsHandler handler = new GetDiRegistrationsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("scope", "project");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("registrations"));
        assertNotNull(map.get("injectionPoints"));
    }

    // =========================================================================
    // Additional edge cases and combined scenarios
    // =========================================================================

    @Test
    void testAnalyzeFileHandler_onServiceFile() throws Exception {
        AnalyzeFileHandler handler = new AnalyzeFileHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals(uri, map.get("uri"));

        // UserService has at least 7 methods
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> methods = (List<Map<String, Object>>) map.get("methods");
        assertTrue(methods.size() >= 7, "UserService should have at least 7 methods");

        // Check complexity of a method
        boolean foundSearchUsers = methods.stream()
                .anyMatch(m -> "searchUsers".equals(m.get("name")));
        assertTrue(foundSearchUsers, "Should find searchUsers method");
    }

    @Test
    void testAnalyzeTypeHandler_interface() throws Exception {
        AnalyzeTypeHandler handler = new AnalyzeTypeHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.service.Validator");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("com.example.service.Validator", map.get("type"));
        assertEquals("interface", map.get("kind"));

        // Validator has at least 1 subtype (UserValidator)
        assertTrue(((Number) map.get("subtypes")).intValue() >= 1);
    }

    @Test
    void testSearchSymbolsHandler_methodSearch() throws Exception {
        SearchSymbolsHandler handler = new SearchSymbolsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("query", "findByName");
        p.put("kind", "method");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("findByName", map.get("query"));
        assertTrue(((Number) map.get("count")).intValue() >= 1,
                "Should find findByName method");
    }

    @Test
    void testTypeHierarchyHandler_withPositionParams() throws Exception {
        TypeHierarchyHandler handler = new TypeHierarchyHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        // Admin class at line 4 (0-based), character 15
        Map<String, Object> p = params(uri, 4, 15);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertNotNull(map.get("type"));
        Map<String, Object> typeInfo = asMap(map.get("type"));
        assertEquals("Admin", typeInfo.get("name"));

        // Admin extends User, which extends Object
        assertNotNull(map.get("supertypes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> supertypes = (List<Map<String, Object>>) map.get("supertypes");
        assertTrue(supertypes.size() >= 1, "Admin should have at least User as supertype");
    }

    @Test
    void testGetDependencyGraphHandler_singleFile() throws Exception {
        GetDependencyGraphHandler handler = new GetDependencyGraphHandler();
        String uri = fileUri("src/com/example/controller/UserController.java");

        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("nodes"));
        assertNotNull(map.get("edges"));

        // UserController imports from model, service, and util packages
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) map.get("edges");
        assertTrue(edges.size() >= 2,
                "UserController should depend on at least model and service packages");
    }

    // =========================================================================
    // maxResults / truncated behavior tests
    // =========================================================================

    @Test
    void testFindAnnotationUsagesHandler_maxResults() throws Exception {
        FindAnnotationUsagesHandler handler = new FindAnnotationUsagesHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "java.lang.Override");
        p.put("maxResults", 1);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
        assertTrue(((Number) map.get("count")).intValue() <= 1,
                "Should return at most 1 result when maxResults=1");
        assertEquals(true, map.get("truncated"),
                "Should be truncated when maxResults limits results");
    }

    @Test
    void testFindAnnotationUsagesHandler_noMaxResults() throws Exception {
        FindAnnotationUsagesHandler handler = new FindAnnotationUsagesHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "java.lang.Override");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertTrue(((Number) map.get("count")).intValue() >= 2,
                "@Override should be used in multiple places");
        assertNull(map.get("truncated"),
                "Should not be truncated when no maxResults is set");
    }

    @Test
    void testFindTypeInstantiationsHandler_maxResults() throws Exception {
        FindTypeInstantiationsHandler handler = new FindTypeInstantiationsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        p.put("maxResults", 1);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("com.example.model.User", map.get("type"));
        assertTrue(((Number) map.get("count")).intValue() <= 1,
                "Should return at most 1 result when maxResults=1");
    }

    @Test
    void testAnalyzeChangeImpactHandler_defaultLimit() throws Exception {
        AnalyzeChangeImpactHandler handler = new AnalyzeChangeImpactHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = params(uri, 33, 18);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("element"));
        assertNotNull(map.get("directImpact"));
        assertNotNull(map.get("transitiveImpact"));
        assertNotNull(map.get("affectedFiles"));
    }

    @Test
    void testAnalyzeChangeImpactHandler_explicitMaxResults() throws Exception {
        AnalyzeChangeImpactHandler handler = new AnalyzeChangeImpactHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = params(uri, 33, 18);
        p.put("maxResults", 1);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("element"));
        @SuppressWarnings("unchecked")
        List<?> directImpact = (List<?>) map.get("directImpact");
        assertTrue(directImpact.size() <= 1,
                "directImpact should be limited to maxResults=1");
    }

    @Test
    void testFindAffectedTestsHandler_defaultLimit() throws Exception {
        FindAffectedTestsHandler handler = new FindAffectedTestsHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        Map<String, Object> p = params(uri, 23, 16);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("element"));
        assertNotNull(map.get("count"));
        assertNotNull(map.get("affectedTests"));
    }

    @Test
    void testFindAffectedTestsHandler_explicitMaxResults() throws Exception {
        FindAffectedTestsHandler handler = new FindAffectedTestsHandler();
        String uri = fileUri("src/com/example/service/UserService.java");

        Map<String, Object> p = params(uri, 23, 16);
        p.put("maxResults", 1);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("element"));
        @SuppressWarnings("unchecked")
        List<?> affectedTests = (List<?>) map.get("affectedTests");
        assertTrue(affectedTests.size() <= 1,
                "affectedTests should be limited to maxResults=1");
    }

    @Test
    void testGetDependencyGraphHandler_maxEdges() throws Exception {
        GetDependencyGraphHandler handler = new GetDependencyGraphHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("maxEdges", 1);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("nodes"));
        assertNotNull(map.get("edges"));
        @SuppressWarnings("unchecked")
        List<?> edges = (List<?>) map.get("edges");
        assertTrue(edges.size() <= 1,
                "edges should be limited to maxEdges=1");
        assertEquals(true, map.get("truncated"),
                "Should be truncated when maxEdges limits edges");
    }

    @Test
    void testFindReferencesHandler_maxResults() throws Exception {
        FindReferencesHandler handler = new FindReferencesHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = params(uri, 12, 15);
        p.put("maxResults", 1);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("element"));
        assertTrue(((Number) map.get("count")).intValue() <= 1,
                "Should return at most 1 reference when maxResults=1");
        assertEquals(true, map.get("truncated"),
                "Should be truncated when maxResults limits results");
    }

    @Test
    void testFindReferencesHandler_noMaxResults_noTruncated() throws Exception {
        FindReferencesHandler handler = new FindReferencesHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = params(uri, 12, 15);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNull(map.get("truncated"),
                "Should not be truncated when no maxResults is set");
        assertTrue(((Number) map.get("count")).intValue() >= 3,
                "User should have many references without limit");
    }

    @Test
    void testFindCastsHandler_maxResults() throws Exception {
        FindCastsHandler handler = new FindCastsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        p.put("maxResults", 1);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    // =========================================================================
    // Codegen insert edit format verification tests
    // =========================================================================

    @Test
    void testGenerateConstructorHandler_insertEditFormat() throws Exception {
        GenerateConstructorHandler handler = new GenerateConstructorHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        Map<String, Object> p = params(uri, 4, 13);
        p.put("generateDefault", true);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals(false, map.get("applied"));
        assertNull(map.get("error"), "Should not return error: " + map.get("error"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) map.get("edits");
        assertNotNull(edits, "Edits must not be null");
        assertFalse(edits.isEmpty(), "Should generate constructor edits");

        Map<String, Object> edit = edits.get(0);
        assertNotNull(edit.get("uri"), "Edit should have uri");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> textEdits = (List<Map<String, Object>>) edit.get("textEdits");
        assertNotNull(textEdits, "Edit should have textEdits (insert format)");
        assertFalse(textEdits.isEmpty(), "textEdits should not be empty");

        Map<String, Object> textEdit = textEdits.get(0);
        assertNotNull(textEdit.get("newText"), "textEdit should have newText");
        String newText = (String) textEdit.get("newText");
        assertTrue(newText.contains("Admin()"), "Should contain default constructor");

        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) textEdit.get("range");
        assertNotNull(range, "textEdit should have range");
        @SuppressWarnings("unchecked")
        Map<String, Object> start = (Map<String, Object>) range.get("start");
        @SuppressWarnings("unchecked")
        Map<String, Object> end = (Map<String, Object>) range.get("end");
        assertEquals(start.get("line"), end.get("line"), "Insert edit should have same start/end line");
        assertEquals(start.get("character"), end.get("character"), "Insert edit should have same start/end character");
    }

    @Test
    void testGenerateGettersSettersHandler_insertEditFormat() throws Exception {
        GenerateGettersSettersHandler handler = new GenerateGettersSettersHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        Map<String, Object> p = params(uri, 4, 13);
        // Admin already has all getters/setters, so handler throws
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("already exist"),
                "Should report all getters/setters already exist");
    }

    @Test
    void testGenerateToStringHandler_insertEditFormat() throws Exception {
        GenerateToStringHandler handler = new GenerateToStringHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        Map<String, Object> p = params(uri, 4, 13);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNull(map.get("error"), "Should not return error: " + map.get("error"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) map.get("edits");
        assertNotNull(edits, "Edits must not be null");
        assertFalse(edits.isEmpty(), "Should generate toString edits");

        Map<String, Object> edit = edits.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> textEdits = (List<Map<String, Object>>) edit.get("textEdits");
        assertNotNull(textEdits, "Should use insert edit format (textEdits)");
        assertFalse(textEdits.isEmpty());

        String newText = (String) textEdits.get(0).get("newText");
        assertTrue(newText.contains("toString"), "Generated code should contain toString");
        assertTrue(newText.contains("department"), "toString should include department field");
    }

    @Test
    void testGenerateEqualsHashCodeHandler_insertEditFormat() throws Exception {
        GenerateEqualsHashCodeHandler handler = new GenerateEqualsHashCodeHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        Map<String, Object> p = params(uri, 4, 13);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNull(map.get("error"), "Should not return error: " + map.get("error"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) map.get("edits");
        assertNotNull(edits, "Edits must not be null");
        assertFalse(edits.isEmpty(), "Should generate equals/hashCode edits");

        Map<String, Object> edit = edits.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> textEdits = (List<Map<String, Object>>) edit.get("textEdits");
        assertNotNull(textEdits, "Should use insert edit format (textEdits)");
        assertFalse(textEdits.isEmpty());

        String newText = (String) textEdits.get(0).get("newText");
        assertTrue(newText.contains("hashCode"), "Generated code should contain hashCode");
        assertTrue(newText.contains("equals"), "Generated code should contain equals");
        assertTrue(newText.contains("Objects.hash"), "hashCode should use Objects.hash");
    }

    @Test
    void testGenerateDelegateMethodsHandler_insertEditFormat() throws Exception {
        GenerateDelegateMethodsHandler handler = new GenerateDelegateMethodsHandler();
        String uri = fileUri("src/com/example/controller/UserController.java");

        Map<String, Object> p = params(uri, 11, 34);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNull(map.get("error"), "Should not return error: " + map.get("error"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) map.get("edits");
        assertNotNull(edits, "Edits must not be null");
        assertFalse(edits.isEmpty(), "Should generate delegate method edits");

        Map<String, Object> edit = edits.get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> textEdits = (List<Map<String, Object>>) edit.get("textEdits");
        assertNotNull(textEdits, "Should use insert edit format (textEdits)");
        assertFalse(textEdits.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> range = (Map<String, Object>) textEdits.get(0).get("range");
        @SuppressWarnings("unchecked")
        Map<String, Object> start = (Map<String, Object>) range.get("start");
        @SuppressWarnings("unchecked")
        Map<String, Object> end = (Map<String, Object>) range.get("end");
        assertEquals(start.get("line"), end.get("line"), "Insert edit should have same start/end line");
        assertEquals(start.get("character"), end.get("character"), "Insert edit should have same start/end character");

        String newText = (String) textEdits.get(0).get("newText");
        assertTrue(newText.contains("userService."), "Delegate methods should forward to userService");
    }

    // =========================================================================
    // Codegen edge cases
    // =========================================================================

    @Test
    void testGenerateConstructorHandler_withFieldNames() throws Exception {
        GenerateConstructorHandler handler = new GenerateConstructorHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        Map<String, Object> p = params(uri, 4, 13);
        p.put("fieldNames", List.of("department"));
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNull(map.get("error"), "Should not return error: " + map.get("error"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) map.get("edits");
        assertNotNull(edits);
        assertFalse(edits.isEmpty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> textEdits = (List<Map<String, Object>>) edits.get(0).get("textEdits");
        String newText = (String) textEdits.get(0).get("newText");
        assertTrue(newText.contains("department"), "Constructor should include department param");
        assertFalse(newText.contains("lastLogin"), "Constructor should NOT include lastLogin param");
    }

    @Test
    void testGenerateConstructorHandler_noTypeAtPosition() throws Exception {
        GenerateConstructorHandler handler = new GenerateConstructorHandler();
        String uri = fileUri("src/com/example/model/User.java");

        // Line 2 is "import java.util.List;" — no enclosing type, resolveTypeAtPosition returns null
        Map<String, Object> p = params(uri, 2, 10);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("No type found at position"),
                "Should return error when no type at position");
    }

    @Test
    void testGenerateGettersSettersHandler_gettersOnly() throws Exception {
        GenerateGettersSettersHandler handler = new GenerateGettersSettersHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        Map<String, Object> p = params(uri, 4, 13);
        p.put("generateGetters", true);
        p.put("generateSetters", false);
        // Admin already has all getters, so handler throws
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("already exist"),
                "Should report all getters/setters already exist");
    }

    @Test
    void testGenerateGettersSettersHandler_settersOnly() throws Exception {
        GenerateGettersSettersHandler handler = new GenerateGettersSettersHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        Map<String, Object> p = params(uri, 4, 13);
        p.put("generateGetters", false);
        p.put("generateSetters", true);
        // Admin already has all setters, so handler throws
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("already exist"),
                "Should report all getters/setters already exist");
    }

    @Test
    void testGenerateEqualsHashCodeHandler_specificFields() throws Exception {
        GenerateEqualsHashCodeHandler handler = new GenerateEqualsHashCodeHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        Map<String, Object> p = params(uri, 4, 13);
        p.put("fieldNames", List.of("department"));
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNull(map.get("error"), "Should not return error: " + map.get("error"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) map.get("edits");
        assertNotNull(edits);
        assertFalse(edits.isEmpty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> textEdits = (List<Map<String, Object>>) edits.get(0).get("textEdits");
        String newText = (String) textEdits.get(0).get("newText");
        assertTrue(newText.contains("department"), "Should include department field");
    }

    @Test
    void testGenerateToStringHandler_specificFields() throws Exception {
        GenerateToStringHandler handler = new GenerateToStringHandler();
        String uri = fileUri("src/com/example/model/Admin.java");

        Map<String, Object> p = params(uri, 4, 13);
        p.put("fieldNames", List.of("department"));
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNull(map.get("error"), "Should not return error: " + map.get("error"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edits = (List<Map<String, Object>>) map.get("edits");
        assertNotNull(edits);
        assertFalse(edits.isEmpty());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> textEdits = (List<Map<String, Object>>) edits.get(0).get("textEdits");
        String newText = (String) textEdits.get(0).get("newText");
        assertTrue(newText.contains("department"), "Should include department in toString");
        assertFalse(newText.contains("lastLogin"), "Should NOT include lastLogin in toString");
    }

    // =========================================================================
    // Search scope tests
    // =========================================================================

    @Test
    void testFindReferencesHandler_withProjectScope() throws Exception {
        FindReferencesHandler handler = new FindReferencesHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = params(uri, 12, 15);
        p.put("scope", "project");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertNotNull(map.get("element"));
        assertTrue(((Number) map.get("count")).intValue() >= 1,
                "User should have references even with project scope");
    }

    @Test
    void testFindFieldWritesHandler_withProjectScope() throws Exception {
        FindFieldWritesHandler handler = new FindFieldWritesHandler();
        String uri = fileUri("src/com/example/model/User.java");

        Map<String, Object> p = params(uri, 14, 19);
        p.put("scope", "project");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindAnnotationUsagesHandler_withProjectScope() throws Exception {
        FindAnnotationUsagesHandler handler = new FindAnnotationUsagesHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "java.lang.Override");
        p.put("scope", "project");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    // =========================================================================
    // Batch file processing tests (fileUris parameter)
    // =========================================================================

    @Test
    void testAnalyzeFileHandler_batchMultipleFiles() throws Exception {
        AnalyzeFileHandler handler = new AnalyzeFileHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("uri", fileUri("src/com/example/model/User.java"));
        p.put("fileUris", List.of(
                fileUri("src/com/example/model/User.java"),
                fileUri("src/com/example/model/Admin.java")));
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        if (result instanceof Map) {
            Map<String, Object> map = asMap(result);
            assertTrue(!map.containsKey("error") || map.get("error") == null,
                    "Should not return error: " + map.get("error"));
        }
    }

    @Test
    void testValidateSyntaxHandler_batchMultipleFiles() throws Exception {
        ValidateSyntaxHandler handler = new ValidateSyntaxHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("uri", fileUri("src/com/example/model/User.java"));
        p.put("fileUris", List.of(
                fileUri("src/com/example/model/User.java"),
                fileUri("src/com/example/service/UserService.java")));
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        if (result instanceof Map) {
            Map<String, Object> map = asMap(result);
            assertTrue(!map.containsKey("error") || map.get("error") == null,
                    "Should not return error: " + map.get("error"));
        }
    }

    // =========================================================================
    // Error handling edge cases
    // =========================================================================

    @Test
    void testFindAnnotationUsagesHandler_nonExistentType() throws Exception {
        FindAnnotationUsagesHandler handler = new FindAnnotationUsagesHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.nonexistent.NoSuchAnnotation");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("not found"),
                "Should return error for non-existent annotation type");
    }

    @Test
    void testFindTypeInstantiationsHandler_nonExistentType() throws Exception {
        FindTypeInstantiationsHandler handler = new FindTypeInstantiationsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.nonexistent.NoSuchType");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("Type not found"),
                "Should return error for non-existent type");
    }

    @Test
    void testAnalyzeChangeImpactHandler_missingArguments() throws Exception {
        AnalyzeChangeImpactHandler handler = new AnalyzeChangeImpactHandler();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(List.of(), MONITOR));
        assertTrue(ex.getMessage().contains("Missing arguments"),
                "Should return error for missing arguments");
    }

    @Test
    void testFindAffectedTestsHandler_missingArguments() throws Exception {
        FindAffectedTestsHandler handler = new FindAffectedTestsHandler();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(List.of(), MONITOR));
        assertTrue(ex.getMessage().contains("Missing arguments"),
                "Should return error for missing arguments");
    }

    @Test
    void testFindImplementationsHandler_nonExistentType() throws Exception {
        FindImplementationsHandler handler = new FindImplementationsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.nonexistent.NoSuchInterface");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("not found"),
                "Should return error for non-existent type");
    }

    @Test
    void testSuggestImportsHandler_unknownType() throws Exception {
        SuggestImportsHandler handler = new SuggestImportsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("typeName", "XyzNonExistentTypeName12345");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals("XyzNonExistentTypeName12345", map.get("typeName"));
        assertEquals(0, ((Number) map.get("count")).intValue(),
                "Should find no suggestions for non-existent type");
    }

    @Test
    void testSearchSymbolsHandler_noResults() throws Exception {
        SearchSymbolsHandler handler = new SearchSymbolsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("query", "NonExistentSymbolXyz12345");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertEquals(0, ((Number) map.get("count")).intValue(),
                "Should find no symbols for non-existent query");
    }

    @Test
    void testCallHierarchyHandler_missingArguments() throws Exception {
        CallHierarchyHandler handler = new CallHierarchyHandler(true);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(List.of(), MONITOR));
        assertTrue(ex.getMessage().contains("Missing arguments"),
                "Should return error for missing arguments");
    }

    @Test
    void testDiagnosticsHandler_multipleFiles() throws Exception {
        DiagnosticsHandler handler = new DiagnosticsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("uris", List.of(
                fileUri("src/com/example/model/User.java"),
                fileUri("src/com/example/model/Admin.java")));
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        assertInstanceOf(List.class, result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnosticsList = (List<Map<String, Object>>) result;
        assertTrue(diagnosticsList.size() >= 2, "Should return diagnostics for both files");
    }

    @Test
    void testGetTypeUsageSummaryHandler_nonExistentType() throws Exception {
        GetTypeUsageSummaryHandler handler = new GetTypeUsageSummaryHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.nonexistent.NoSuchType");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("Type not found"),
                "Should return error for non-existent type");
    }

    @Test
    void testTypeHierarchyHandler_nonExistentType() throws Exception {
        TypeHierarchyHandler handler = new TypeHierarchyHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.nonexistent.NoSuchType");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> handler.execute(args(p), MONITOR));
        assertTrue(ex.getMessage().contains("Type not found"),
                "Should return error for non-existent type");
    }

    // =========================================================================
    // FindImplementationsHandler edge cases
    // =========================================================================

    @Test
    void testFindImplementationsHandler_withPositionParams() throws Exception {
        FindImplementationsHandler handler = new FindImplementationsHandler();
        String uri = fileUri("src/com/example/service/Validator.java");

        Map<String, Object> p = params(uri, 2, 17);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertTrue(!map.containsKey("error") || map.get("error") == null,
                "Should not return error: " + map.get("error"));
    }

    @Test
    void testFindImplementationsHandler_concreteClass() throws Exception {
        FindImplementationsHandler handler = new FindImplementationsHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);
        assertTrue(((Number) map.get("count")).intValue() >= 1,
                "User should have at least 1 subtype (Admin)");
    }
}
