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
package org.eclipse.mcp.jdtls.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.mcp.jdtls.handlers.navigation.GetDocumentSymbolsHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetEnclosingElementHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetFieldAtPositionHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetHoverInfoHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetJavadocHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetMethodAtPositionHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetSignatureHelpHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetSuperMethodHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetSymbolInfoHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetTypeAtPositionHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GetTypeMembersHandler;
import org.eclipse.mcp.jdtls.handlers.navigation.GoToDefinitionHandler;
import org.junit.jupiter.api.Test;

/**
 * Tests for the navigation handler classes in
 * {@code org.eclipse.mcp.jdtls.handlers.navigation}.
 *
 * <p>Each test method exercises one handler against the "simple-java" test
 * project that is imported by {@link AbstractHandlerTest}.</p>
 */
class NavigationHandlerTest extends AbstractHandlerTest {

    // -- File paths (relative to project root) --------------------------------

    private static final String USER_JAVA = "src/com/example/model/User.java";
    private static final String ADMIN_JAVA = "src/com/example/model/Admin.java";
    private static final String USER_SERVICE_JAVA = "src/com/example/service/UserService.java";

    // -------------------------------------------------------------------------
    // 1. GoToDefinitionHandler
    // -------------------------------------------------------------------------

    @Test
    void testGoToDefinition_UserReferenceInUserService() throws Exception {
        GoToDefinitionHandler handler = new GoToDefinitionHandler();

        // UserService.java line 24 (1-based) / 23 (0-based): "public void addUser(User user) {"
        // "User" starts at character 25
        String uri = fileUri(USER_SERVICE_JAVA);
        Object result = handler.execute(args(params(uri, 23, 25)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        // Should navigate to User type definition
        assertEquals("User", map.get("element"));
        assertEquals("type", map.get("kind"));

        // Should point to User.java
        assertNotNull(map.get("uri"));
        String targetUri = (String) map.get("uri");
        assertTrue(targetUri.contains("User.java"), "URI should point to User.java");

        // Should report line and character
        assertNotNull(map.get("line"));
        assertNotNull(map.get("character"));
    }

    // -------------------------------------------------------------------------
    // 2. GetHoverInfoHandler
    // -------------------------------------------------------------------------

    @Test
    void testGetHoverInfo_MethodName() throws Exception {
        GetHoverInfoHandler handler = new GetHoverInfoHandler();

        // User.java line 34 (1-based) / 33 (0-based): "public String getName() {"
        // "getName" starts at character 18
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 33, 20)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertEquals("getName", map.get("element"));
        assertEquals("method", map.get("kind"));

        // Should have return type
        assertEquals("String", map.get("returnType"));

        // Should have modifiers
        assertNotNull(map.get("modifiers"));
        @SuppressWarnings("unchecked")
        List<String> modifiers = (List<String>) map.get("modifiers");
        assertTrue(modifiers.contains("public"));

        // Should have declaring type
        assertEquals("com.example.model.User", map.get("declaringType"));

        // Should have signature
        assertNotNull(map.get("signature"));
    }

    // -------------------------------------------------------------------------
    // 3. GetJavadocHandler
    // -------------------------------------------------------------------------

    @Test
    void testGetJavadoc_UserConstructor() throws Exception {
        GetJavadocHandler handler = new GetJavadocHandler();

        // User.java line 27 (1-based) / 26 (0-based): "public User(String name, int age, String email) {"
        // "User" constructor name at character 11
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 26, 11)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertEquals("User", map.get("element"));

        // Should have structured javadoc
        assertNotNull(map.get("javadoc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> javadoc = (Map<String, Object>) map.get("javadoc");

        // Should have a description
        assertNotNull(javadoc.get("description"));
        String description = (String) javadoc.get("description");
        assertTrue(description.contains("Creates a new User instance"),
                "Description should mention creating a User instance");

        // Should have @param tags for name, age, email
        assertNotNull(javadoc.get("params"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> params = (List<Map<String, String>>) javadoc.get("params");
        assertTrue(params.size() >= 3, "Should have at least 3 @param tags");

        // Verify param names
        boolean hasName = false;
        boolean hasAge = false;
        boolean hasEmail = false;
        for (Map<String, String> param : params) {
            String name = param.get("name");
            if ("name".equals(name)) hasName = true;
            if ("age".equals(name)) hasAge = true;
            if ("email".equals(name)) hasEmail = true;
        }
        assertTrue(hasName, "Should have @param for name");
        assertTrue(hasAge, "Should have @param for age");
        assertTrue(hasEmail, "Should have @param for email");
    }

    // -------------------------------------------------------------------------
    // 4. GetSymbolInfoHandler
    // -------------------------------------------------------------------------

    @Test
    void testGetSymbolInfo_Field() throws Exception {
        GetSymbolInfoHandler handler = new GetSymbolInfoHandler();

        // User.java line 15 (1-based) / 14 (0-based): "private String name;"
        // "name" at character 19
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 14, 19)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertEquals("field", map.get("kind"));
        assertEquals("name", map.get("name"));
        assertEquals("String", map.get("type"));
        assertEquals("com.example.model.User", map.get("declaringType"));

        @SuppressWarnings("unchecked")
        List<String> modifiers = (List<String>) map.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("private"));
    }

    @Test
    void testGetSymbolInfo_Method() throws Exception {
        GetSymbolInfoHandler handler = new GetSymbolInfoHandler();

        // User.java line 34 (1-based) / 33 (0-based): "public String getName() {"
        // "getName" at character 18
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 33, 20)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertEquals("method", map.get("kind"));
        assertEquals("getName", map.get("name"));
        assertEquals("String", map.get("returnType"));
        assertEquals("com.example.model.User", map.get("declaringType"));
        assertEquals(false, map.get("isConstructor"));
    }

    @Test
    void testGetSymbolInfo_Type() throws Exception {
        GetSymbolInfoHandler handler = new GetSymbolInfoHandler();

        // User.java line 13 (1-based) / 12 (0-based): "public class User {"
        // "User" at character 13
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 12, 14)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        // When pointing at a type declaration, kind should be "class"
        String kind = (String) map.get("kind");
        assertTrue("type".equals(kind) || "class".equals(kind),
                "Kind should be 'type' or 'class', was: " + kind);
        assertEquals("User", map.get("name"));
    }

    // -------------------------------------------------------------------------
    // 5. GetFieldAtPositionHandler
    // -------------------------------------------------------------------------

    @Test
    void testGetFieldAtPosition_NameField() throws Exception {
        GetFieldAtPositionHandler handler = new GetFieldAtPositionHandler();

        // User.java line 15 (1-based) / 14 (0-based): "private String name;"
        // "name" at character 19
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 14, 19)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertFalse(map.containsKey("error"), "Should not return an error");
        assertEquals("name", map.get("name"));
        assertEquals("String", map.get("type"));
        assertEquals("com.example.model.User", map.get("declaringType"));
        assertEquals(false, map.get("isEnumConstant"));

        @SuppressWarnings("unchecked")
        List<String> modifiers = (List<String>) map.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("private"));
    }

    // -------------------------------------------------------------------------
    // 6. GetMethodAtPositionHandler
    // -------------------------------------------------------------------------

    @Test
    void testGetMethodAtPosition_GetName() throws Exception {
        GetMethodAtPositionHandler handler = new GetMethodAtPositionHandler();

        // User.java line 34 (1-based) / 33 (0-based): "public String getName() {"
        // "getName" at character 18
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 33, 20)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertFalse(map.containsKey("error"), "Should not return an error");
        assertEquals("getName", map.get("name"));
        assertEquals("String", map.get("returnType"));
        assertEquals("com.example.model.User", map.get("declaringType"));
        assertEquals(false, map.get("isConstructor"));

        // getName() has no parameters
        @SuppressWarnings("unchecked")
        List<String> paramNames = (List<String>) map.get("parameterNames");
        assertNotNull(paramNames);
        assertTrue(paramNames.isEmpty(), "getName() should have no parameters");

        @SuppressWarnings("unchecked")
        List<String> modifiers = (List<String>) map.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));
    }

    // -------------------------------------------------------------------------
    // 7. GetTypeAtPositionHandler
    // -------------------------------------------------------------------------

    @Test
    void testGetTypeAtPosition_UserClass() throws Exception {
        GetTypeAtPositionHandler handler = new GetTypeAtPositionHandler();

        // User.java line 13 (1-based) / 12 (0-based): "public class User {"
        // "User" at character 13
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 12, 14)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertFalse(map.containsKey("error"), "Should not return an error");
        assertEquals("User", map.get("name"));
        assertEquals("com.example.model.User", map.get("fullyQualifiedName"));
        assertEquals("class", map.get("kind"));

        @SuppressWarnings("unchecked")
        List<String> modifiers = (List<String>) map.get("modifiers");
        assertNotNull(modifiers);
        assertTrue(modifiers.contains("public"));

        // URI should point to User.java
        assertNotNull(map.get("uri"));
        String targetUri = (String) map.get("uri");
        assertTrue(targetUri.contains("User.java"));
    }

    // -------------------------------------------------------------------------
    // 8. GetSignatureHelpHandler
    // -------------------------------------------------------------------------

    @Test
    void testGetSignatureHelp_Constructor() throws Exception {
        GetSignatureHelpHandler handler = new GetSignatureHelpHandler();

        // User.java line 27 (1-based) / 26 (0-based): "public User(String name, int age, String email) {"
        // "User" constructor at character 11
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 26, 11)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertFalse(map.containsKey("error"), "Should not return an error");
        assertEquals("User", map.get("method"));
        assertEquals("com.example.model.User", map.get("declaringType"));

        // Should have 3 parameters: name, age, email
        assertNotNull(map.get("parameters"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> parameters = (List<Map<String, String>>) map.get("parameters");
        assertEquals(3, parameters.size(), "Constructor should have 3 parameters");

        assertEquals("name", parameters.get(0).get("name"));
        assertEquals("String", parameters.get(0).get("type"));

        assertEquals("age", parameters.get(1).get("name"));
        assertEquals("int", parameters.get(1).get("type"));

        assertEquals("email", parameters.get(2).get("name"));
        assertEquals("String", parameters.get(2).get("type"));
    }

    // -------------------------------------------------------------------------
    // 9. GetSuperMethodHandler
    // -------------------------------------------------------------------------

    @Test
    void testGetSuperMethod_GetDisplayNameOverride() throws Exception {
        GetSuperMethodHandler handler = new GetSuperMethodHandler();

        // Admin.java line 32 (1-based) / 31 (0-based): "public String getDisplayName() {"
        // "getDisplayName" at character 18
        String uri = fileUri(ADMIN_JAVA);
        Object result = handler.execute(args(params(uri, 31, 20)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertFalse(map.containsKey("error"), "Should not return an error");
        assertEquals("getDisplayName", map.get("method"));
        assertEquals("com.example.model.Admin", map.get("declaringType"));

        // Should find the super method in User
        assertNotNull(map.get("superMethod"), "Should find the super method in User");
        @SuppressWarnings("unchecked")
        Map<String, Object> superMethod = (Map<String, Object>) map.get("superMethod");

        assertEquals("getDisplayName", superMethod.get("name"));
        assertEquals("com.example.model.User", superMethod.get("declaringType"));

        // Should have a URI pointing to User.java
        assertNotNull(superMethod.get("uri"));
        String superUri = (String) superMethod.get("uri");
        assertTrue(superUri.contains("User.java"),
                "Super method URI should point to User.java");
    }

    @Test
    void testGetSuperMethod_NoSuperMethod() throws Exception {
        GetSuperMethodHandler handler = new GetSuperMethodHandler();

        // Admin.java line 36 (1-based) / 35 (0-based): "public boolean hasPermission(String permission) {"
        // "hasPermission" at character 19
        String uri = fileUri(ADMIN_JAVA);
        Object result = handler.execute(args(params(uri, 35, 20)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertEquals("hasPermission", map.get("method"));
        // hasPermission does not override anything
        assertNull(map.get("superMethod"),
                "hasPermission() should not have a super method");
    }

    // -------------------------------------------------------------------------
    // 10. GetDocumentSymbolsHandler
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void testGetDocumentSymbols_UserJava() throws Exception {
        GetDocumentSymbolsHandler handler = new GetDocumentSymbolsHandler();

        String uri = fileUri(USER_JAVA);
        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertEquals(uri, map.get("uri"));

        // Should have a list of symbols
        assertNotNull(map.get("symbols"));
        List<Map<String, Object>> symbols = (List<Map<String, Object>>) map.get("symbols");
        assertFalse(symbols.isEmpty(), "Should have at least one top-level symbol");

        // First top-level symbol should be the User class
        Map<String, Object> userClass = symbols.get(0);
        assertEquals("User", userClass.get("name"));
        assertEquals("class", userClass.get("kind"));

        // User class should have children (fields and methods)
        List<Map<String, Object>> children = (List<Map<String, Object>>) userClass.get("children");
        assertNotNull(children, "User class should have children");
        assertFalse(children.isEmpty(), "User class should have fields and methods");

        // Verify that we find some expected members
        boolean hasNameField = false;
        boolean hasGetNameMethod = false;
        boolean hasConstructor = false;
        for (Map<String, Object> child : children) {
            String name = (String) child.get("name");
            String kind = (String) child.get("kind");
            if ("name".equals(name) && "field".equals(kind)) hasNameField = true;
            if ("getName".equals(name) && "method".equals(kind)) hasGetNameMethod = true;
            if ("User".equals(name) && "constructor".equals(kind)) hasConstructor = true;
        }
        assertTrue(hasNameField, "Should find 'name' field");
        assertTrue(hasGetNameMethod, "Should find 'getName' method");
        assertTrue(hasConstructor, "Should find User constructor");
    }

    // -------------------------------------------------------------------------
    // 11. GetTypeMembersHandler
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void testGetTypeMembers_UserClass() throws Exception {
        GetTypeMembersHandler handler = new GetTypeMembersHandler();

        Map<String, Object> p = new HashMap<>();
        p.put("fullyQualifiedName", "com.example.model.User");
        Object result = handler.execute(args(p), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        assertFalse(map.containsKey("error"), "Should not return an error");
        assertEquals("com.example.model.User", map.get("type"));

        // Should have methods
        List<Map<String, Object>> methods = (List<Map<String, Object>>) map.get("methods");
        assertNotNull(methods);
        assertFalse(methods.isEmpty(), "User should have methods");

        // Should have fields
        List<Map<String, Object>> fields = (List<Map<String, Object>>) map.get("fields");
        assertNotNull(fields);
        assertFalse(fields.isEmpty(), "User should have fields");

        // Verify some known methods exist
        boolean hasGetName = false;
        boolean hasSetName = false;
        boolean hasIsAdult = false;
        for (Map<String, Object> method : methods) {
            String name = (String) method.get("name");
            if ("getName".equals(name)) hasGetName = true;
            if ("setName".equals(name)) hasSetName = true;
            if ("isAdult".equals(name)) hasIsAdult = true;
        }
        assertTrue(hasGetName, "Should have getName method");
        assertTrue(hasSetName, "Should have setName method");
        assertTrue(hasIsAdult, "Should have isAdult method");

        // Verify some known fields exist
        boolean hasNameField = false;
        boolean hasAgeField = false;
        for (Map<String, Object> field : fields) {
            String name = (String) field.get("name");
            if ("name".equals(name)) hasNameField = true;
            if ("age".equals(name)) hasAgeField = true;
        }
        assertTrue(hasNameField, "Should have name field");
        assertTrue(hasAgeField, "Should have age field");

        // Count should be positive
        assertNotNull(map.get("count"));
        int count = ((Number) map.get("count")).intValue();
        assertTrue(count > 0, "Total member count should be positive");
    }

    // -------------------------------------------------------------------------
    // 12. GetEnclosingElementHandler
    // -------------------------------------------------------------------------

    @Test
    void testGetEnclosingElement_InsideMethodBody() throws Exception {
        GetEnclosingElementHandler handler = new GetEnclosingElementHandler();

        // User.java line 35 (1-based) / 34 (0-based): "return name;" (inside getName() method body)
        // character 15 points inside the statement
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 34, 15)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        // Should have enclosing method
        assertNotNull(map.get("enclosingMethod"), "Should have an enclosing method");
        @SuppressWarnings("unchecked")
        Map<String, Object> enclosingMethod = (Map<String, Object>) map.get("enclosingMethod");
        assertEquals("getName", enclosingMethod.get("name"));

        // Should have enclosing type
        assertNotNull(map.get("enclosingType"), "Should have an enclosing type");
        @SuppressWarnings("unchecked")
        Map<String, Object> enclosingType = (Map<String, Object>) map.get("enclosingType");
        assertEquals("User", enclosingType.get("name"));
        assertEquals("com.example.model.User", enclosingType.get("fullyQualifiedName"));
        assertEquals("class", enclosingType.get("kind"));

        // Should have enclosing package
        assertNotNull(map.get("enclosingPackage"), "Should have an enclosing package");
        @SuppressWarnings("unchecked")
        Map<String, Object> enclosingPackage = (Map<String, Object>) map.get("enclosingPackage");
        assertEquals("com.example.model", enclosingPackage.get("name"));
    }

    @Test
    void testGetEnclosingElement_InsideFieldDeclaration() throws Exception {
        GetEnclosingElementHandler handler = new GetEnclosingElementHandler();

        // User.java line 15 (1-based) / 14 (0-based): "private String name;"
        // Inside a field, not a method -- enclosingMethod should be null
        String uri = fileUri(USER_JAVA);
        Object result = handler.execute(args(params(uri, 14, 10)), MONITOR);

        assertNotNull(result);
        Map<String, Object> map = asMap(result);

        // Enclosing method should be null (we are at field level, not in a method)
        assertNull(map.get("enclosingMethod"),
                "Should not have an enclosing method when on a field declaration");

        // Should still have enclosing type
        assertNotNull(map.get("enclosingType"), "Should have an enclosing type");
        @SuppressWarnings("unchecked")
        Map<String, Object> enclosingType = (Map<String, Object>) map.get("enclosingType");
        assertEquals("User", enclosingType.get("name"));
    }
}
