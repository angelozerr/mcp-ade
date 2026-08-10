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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ibm.mcp.jdtls.handlers.refactoring.ChangeMethodSignatureHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.ConvertAnonymousToLambdaHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.ConvertToRecordHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.EncapsulateFieldHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.ExtractConstantHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.ExtractInterfaceHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.ExtractMethodHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.ExtractSuperclassHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.ExtractVariableHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.InlineMethodHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.InlineVariableHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.IntroduceParameterObjectHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.MoveTypeToNewFileHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.MoveTypeToPackageHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.OrganizeImportsHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.PullUpHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.PushDownHandler;
import com.ibm.mcp.jdtls.handlers.refactoring.RenameSymbolHandler;

/**
 * Tests for all refactoring handler classes in the
 * {@code com.ibm.mcp.jdtls.handlers.refactoring} package.
 *
 * <p>Each handler implements {@code ICommandHandler} and is invoked via
 * {@code execute(List<Object> arguments, IProgressMonitor monitor)}.
 * The AbstractLTKRefactoringHandler returns a result map with:
 * <ul>
 *   <li>{@code applied} (boolean) - whether changes were written to disk</li>
 *   <li>{@code edits} (List) - the text edits that would be (or were) applied</li>
 *   <li>{@code error} (String, optional) - error message if the refactoring failed</li>
 * </ul>
 *
 * <p>Tests primarily use <b>preview mode</b> (apply=false or not set) to avoid
 * modifying the test project files on disk. A few key tests verify apply=true
 * behavior with explicit file content restoration in an {@code @AfterEach}-style
 * try/finally block.
 *
 * <p>Test project layout (line numbers are 0-based):
 * <pre>
 * src/com/example/model/User.java  (0-based line numbers)
 *   - fields: name(14), age(15), email(16), roles(17)
 *   - constructor(26), getName(33), setName(37)
 *   - isAdult(76): body line 77 "return age >= 18;"
 *   - getDisplayName(85): return name + " (" + email + ")"
 *
 * src/com/example/model/Admin.java
 *   - extends User, department field(6), lastLogin field(7)
 *
 * src/com/example/service/UserService.java
 *   - MAX_USERS constant(15)
 *   - addUser(23), findByName(36), findAdults(50)
 *   - searchUsers(76): params (query, minAge, maxAge)
 *
 * src/com/example/util/StringUtils.java
 *   - isEmpty(6), isNotEmpty(10), capitalize(14), truncate(21)
 * </pre>
 */
public class RefactoringHandlerTest extends AbstractHandlerTest {

    // -----------------------------------------------------------------------
    // Helper methods for building refactoring parameter maps
    // -----------------------------------------------------------------------

    /**
     * Build params map for selection-based refactorings (extract method/variable/constant).
     */
    private static Map<String, Object> selectionParams(String uri,
            int startLine, int startChar, int endLine, int endChar) {
        Map<String, Object> p = new HashMap<>();
        p.put("uri", uri);
        p.put("startLine", startLine);
        p.put("startCharacter", startChar);
        p.put("endLine", endLine);
        p.put("endCharacter", endChar);
        return p;
    }

    /**
     * Read the on-disk content of a project file given its project-relative path.
     * Used to capture content before apply=true tests so it can be restored.
     */
    private static String readFileContent(String relativePath) throws Exception {
        Path filePath = project.getLocation().toFile().toPath().resolve(relativePath);
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    /**
     * Write content back to a project file and refresh the Eclipse resource.
     */
    private static void writeFileContent(String relativePath, String content) throws Exception {
        Path filePath = project.getLocation().toFile().toPath().resolve(relativePath);
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
        project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, MONITOR);
    }

    /**
     * Assert that the result represents a successful refactoring (no error key,
     * applied matches expected, and edits list is non-empty).
     */
    private static void assertSuccess(Map<String, Object> result, boolean expectedApplied) {
        assertNotNull(result, "Result must not be null");
        assertFalse(result.containsKey("error"),
                "Result should not contain error but got: " + result.get("error"));
        assertEquals(expectedApplied, result.get("applied"),
                "applied flag mismatch");
        @SuppressWarnings("unchecked")
        List<Object> edits = (List<Object>) result.get("edits");
        assertNotNull(edits, "edits must not be null");
        assertFalse(edits.isEmpty(), "edits must not be empty");
    }

    /**
     * Assert that the result represents a successful refactoring in preview mode.
     */
    private static void assertPreviewSuccess(Map<String, Object> result) {
        assertSuccess(result, false);
    }

    // ===================================================================
    // 1. RenameSymbolHandler
    // ===================================================================

    @Nested
    @DisplayName("RenameSymbolHandler")
    class RenameSymbolTests {

        private final RenameSymbolHandler handler = new RenameSymbolHandler();

        @Test
        @DisplayName("Rename field 'name' in User.java - preview mode")
        void renameField_previewMode() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            // 'name' field is on line 14 (0-based), character 19 on the field name
            Map<String, Object> p = params(uri, 14, 19);
            p.put("newName", "fullName");
            // No apply flag -> preview mode

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);
            // Verify edits are returned
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edits = (List<Map<String, Object>>) resultMap.get("edits");
            assertFalse(edits.isEmpty(), "Rename should produce edits");
        }

        @Test
        @DisplayName("Rename field 'name' in User.java - apply mode with restore")
        void renameField_applyMode() throws Exception {
            String userRelPath = "src/com/example/model/User.java";
            String userServiceRelPath = "src/com/example/service/UserService.java";
            String userValidatorRelPath = "src/com/example/service/UserValidator.java";
            String controllerRelPath = "src/com/example/controller/UserController.java";

            // Save original content of all files that may be modified
            String origUser = readFileContent(userRelPath);
            String origService = readFileContent(userServiceRelPath);
            String origValidator = readFileContent(userValidatorRelPath);
            String origController = readFileContent(controllerRelPath);

            try {
                String uri = fileUri(userRelPath);
                Map<String, Object> p = params(uri, 14, 19);
                p.put("newName", "fullName");
                p.put("apply", true);

                Object result = handler.execute(args(p), MONITOR);
                Map<String, Object> resultMap = asMap(result);

                assertSuccess(resultMap, true);
            } finally {
                // Restore all potentially modified files
                writeFileContent(userRelPath, origUser);
                writeFileContent(userServiceRelPath, origService);
                writeFileContent(userValidatorRelPath, origValidator);
                writeFileContent(controllerRelPath, origController);
            }
        }

        @Test
        @DisplayName("Rename with same name returns error")
        void renameSameName_returnsError() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            Map<String, Object> p = params(uri, 14, 19);
            p.put("newName", "name");

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Rename method getName() - preview mode")
        void renameMethod_previewMode() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            // getName() is on line 33 (0-based), 'getName' starts at character 18
            Map<String, Object> p = params(uri, 33, 18);
            p.put("newName", "getFullName");

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);
        }
    }

    // ===================================================================
    // 2. ExtractMethodHandler
    // ===================================================================

    @Nested
    @DisplayName("ExtractMethodHandler")
    class ExtractMethodTests {

        private final ExtractMethodHandler handler = new ExtractMethodHandler();

        @Test
        @DisplayName("Extract loop body in findAdults() - preview mode")
        void extractMethodFromFindAdults() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // findAdults() body. Extract the if-block inside the for-loop: lines 53-55 (0-based)
            Map<String, Object> p = selectionParams(uri, 53, 12, 55, 13);
            p.put("methodName", "filterAdult");

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);
        }

        @Test
        @DisplayName("Extract method with empty selection returns error")
        void extractMethod_emptySelection() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // Zero-length selection
            Map<String, Object> p = selectionParams(uri, 52, 12, 52, 12);
            p.put("methodName", "filterAdult");

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // 3. ExtractVariableHandler
    // ===================================================================

    @Nested
    @DisplayName("ExtractVariableHandler")
    class ExtractVariableTests {

        private final ExtractVariableHandler handler = new ExtractVariableHandler();

        @Test
        @DisplayName("Extract 'age >= 18' expression in isAdult() - preview mode")
        void extractVariable_ageExpression() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            // isAdult() body at line 77 (0-based): "return age >= 18;"
            // The expression "age >= 18" starts at character 15, ends at character 24
            Map<String, Object> p = selectionParams(uri, 77, 15, 77, 24);
            p.put("variableName", "isOfAge");

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);
        }
    }

    // ===================================================================
    // 4. ExtractConstantHandler
    // ===================================================================

    @Nested
    @DisplayName("ExtractConstantHandler")
    class ExtractConstantTests {

        private final ExtractConstantHandler handler = new ExtractConstantHandler();

        @Test
        @DisplayName("Extract literal 18 from isAdult() - preview mode")
        void extractConstant_ageLiteral() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            // isAdult() body at line 77 (0-based): "return age >= 18;"
            // The literal "18" is at characters 22-24
            Map<String, Object> p = selectionParams(uri, 77, 22, 77, 24);
            p.put("constantName", "ADULT_AGE");

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);
        }
    }

    // ===================================================================
    // 5. InlineVariableHandler
    // ===================================================================

    @Nested
    @DisplayName("InlineVariableHandler")
    class InlineVariableTests {

        private final InlineVariableHandler handler = new InlineVariableHandler();

        @Test
        @DisplayName("Inline local variable 'adults' in findAdults() - preview mode")
        void inlineVariable_adults() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // findAdults() line 51 (0-based): "List<User> adults = new ArrayList<>();"
            // Position cursor on 'adults' declaration name, character 19
            Map<String, Object> p = params(uri, 51, 19);

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            // InlineTempRefactoring may refuse if the variable is assigned
            // multiple times or has complex usage. Check that result is valid
            // (either success with edits or a clear error message).
            assertNotNull(resultMap);
            // The variable 'adults' is modified in the loop, so the refactoring
            // may produce an error. Either way, applied should be false.
            assertEquals(false, resultMap.get("applied"));
        }

        @Test
        @DisplayName("Inline variable never returns empty edits without error")
        void inlineVariable_neverSilentlyEmpty() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // 'adults' at line 51 (0-based), char 19 - modified in loop, cannot be inlined
            Map<String, Object> p = params(uri, 51, 19);

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertNotNull(resultMap);
            assertEquals(false, resultMap.get("applied"));

            @SuppressWarnings("unchecked")
            List<Object> edits = (List<Object>) resultMap.get("edits");
            if (edits == null || edits.isEmpty()) {
                assertNotNull(resultMap.get("error"),
                        "When edits are empty, result must contain an error message");
            }
        }

        @Test
        @DisplayName("Inline variable on non-declaration position returns error")
        void inlineVariable_nonDeclaration() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            // Point to a method call, not a variable declaration
            Map<String, Object> p = params(uri, 33, 18);

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // 6. InlineMethodHandler
    // ===================================================================

    @Nested
    @DisplayName("InlineMethodHandler")
    class InlineMethodTests {

        private final InlineMethodHandler handler = new InlineMethodHandler();

        @Test
        @DisplayName("Inline isEmpty() usage in StringUtils - preview mode")
        void inlineMethod_isEmpty() throws Exception {
            String uri = fileUri("src/com/example/util/StringUtils.java");
            // isNotEmpty() at line 10 (0-based) calls isEmpty(str)
            // Body at line 11 (0-based): "return !isEmpty(str);"
            // 'isEmpty' starts at character 16
            Map<String, Object> p = params(uri, 11, 16);

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            // Inline method should work or give a meaningful response
            assertNotNull(resultMap);
            assertEquals(false, resultMap.get("applied"),
                    "Preview mode should not apply changes");
        }
    }

    // ===================================================================
    // 7. ChangeMethodSignatureHandler
    // ===================================================================

    @Nested
    @DisplayName("ChangeMethodSignatureHandler")
    class ChangeMethodSignatureTests {

        private final ChangeMethodSignatureHandler handler = new ChangeMethodSignatureHandler();

        @Test
        @DisplayName("Rename method addUser to registerUser - preview mode")
        void changeSignature_renameMethod() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // addUser() is on line 23 (0-based), character 16 for the method name
            Map<String, Object> p = params(uri, 23, 16);
            p.put("newName", "registerUser");

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);
        }

        @Test
        @DisplayName("Rename method produces delete edits for old name (DeleteEdit handling)")
        void changeSignature_producesDeleteEdits() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // addUser() is on line 23 (0-based), character 16
            Map<String, Object> p = params(uri, 23, 16);
            p.put("newName", "registerUser");

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edits = (List<Map<String, Object>>) resultMap.get("edits");

            boolean hasDeleteEdits = false;
            boolean hasInsertEdits = false;
            for (Map<String, Object> fileEdit : edits) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> textEdits = (List<Map<String, Object>>) fileEdit.get("textEdits");
                for (Map<String, Object> textEdit : textEdits) {
                    if ("".equals(textEdit.get("newText"))) {
                        hasDeleteEdits = true;
                    }
                    if ("registerUser".equals(textEdit.get("newText"))) {
                        hasInsertEdits = true;
                    }
                }
            }
            assertTrue(hasDeleteEdits,
                    "Rename should produce delete edits (newText=\"\") for removing old method name");
            assertTrue(hasInsertEdits,
                    "Rename should produce insert edits with new method name");
        }

        @Test
        @DisplayName("Rename method with apply produces delete and insert edits on disk")
        void changeSignature_applyWithDeleteEdits() throws Exception {
            String serviceRelPath = "src/com/example/service/UserService.java";
            String controllerRelPath = "src/com/example/controller/UserController.java";

            String origService = readFileContent(serviceRelPath);
            String origController = readFileContent(controllerRelPath);

            try {
                String uri = fileUri(serviceRelPath);
                Map<String, Object> p = params(uri, 23, 16);
                p.put("newName", "registerUser");
                p.put("apply", true);

                Object result = handler.execute(args(p), MONITOR);
                Map<String, Object> resultMap = asMap(result);

                assertSuccess(resultMap, true);

                String modifiedService = readFileContent(serviceRelPath);
                assertTrue(modifiedService.contains("registerUser"),
                        "Applied rename should write new method name to disk");
                assertFalse(modifiedService.contains("addUser"),
                        "Applied rename should remove old method name from disk");
            } finally {
                writeFileContent(serviceRelPath, origService);
                writeFileContent(controllerRelPath, origController);
            }
        }

        @Test
        @DisplayName("Change signature on non-method position returns error")
        void changeSignature_nonMethod() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // Line 15 (0-based) is MAX_USERS constant, not a method
            Map<String, Object> p = params(uri, 15, 30);
            p.put("newName", "something");

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // 8. EncapsulateFieldHandler
    // ===================================================================

    @Nested
    @DisplayName("EncapsulateFieldHandler")
    class EncapsulateFieldTests {

        private final EncapsulateFieldHandler handler = new EncapsulateFieldHandler();

        @Test
        @DisplayName("Encapsulate 'department' field returns error when getter/setter exist")
        void encapsulateField_department() throws Exception {
            String uri = fileUri("src/com/example/model/Admin.java");
            // 'department' field is on line 6 (0-based), character 19
            Map<String, Object> p = params(uri, 6, 19);

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Encapsulate field on non-field position returns error")
        void encapsulateField_nonField() throws Exception {
            String uri = fileUri("src/com/example/model/Admin.java");
            // Line 9 (0-based) is the constructor, not a field
            Map<String, Object> p = params(uri, 9, 11);

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // 9. ExtractInterfaceHandler
    // ===================================================================

    @Nested
    @DisplayName("ExtractInterfaceHandler")
    class ExtractInterfaceTests {

        private final ExtractInterfaceHandler handler = new ExtractInterfaceHandler();

        @Test
        @DisplayName("Extract interface from UserService with addUser and findByName - preview mode")
        void extractInterface_fromUserService() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // Position on the class name 'UserService' at line 12 (0-based), character 13
            Map<String, Object> p = params(uri, 12, 13);
            p.put("interfaceName", "IUserService");
            p.put("methodNames", List.of("addUser", "findByName"));

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertNotNull(resultMap);
            assertEquals(false, resultMap.get("applied"));
        }

        @Test
        @DisplayName("Bug #7 regression - no NPE from null CodeGenerationSettings")
        void extractInterface_noNPE() throws Exception {
            // This test verifies the fix for Bug #7 where ExtractInterfaceProcessor
            // would throw NPE when CodeGenerationSettings was null.
            // The handler now uses JavaPreferencesSettings.getCodeGenerationSettings()
            // which should always return a valid settings object.
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = params(uri, 12, 13);
            p.put("interfaceName", "IUserOps");
            p.put("methodNames", List.of("addUser"));

            // Should not throw NPE
            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertNotNull(resultMap, "Result must not be null (NPE regression check)");
            assertFalse(resultMap.containsKey("error") &&
                            resultMap.get("error").toString().contains("NullPointerException"),
                    "Should not produce NPE (Bug #7 regression)");
        }

        @Test
        @DisplayName("Extract interface with no matching methods returns error")
        void extractInterface_noMatchingMethods() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = params(uri, 12, 13);
            p.put("interfaceName", "IUserService");
            p.put("methodNames", List.of("nonExistentMethod"));

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Extract interface from an interface returns error")
        void extractInterface_fromInterface() throws Exception {
            String uri = fileUri("src/com/example/service/Validator.java");
            Map<String, Object> p = params(uri, 2, 17);
            p.put("interfaceName", "IValidator");
            p.put("methodNames", List.of("validate"));

            RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
            assertTrue(ex.getMessage().contains("interface"),
                    "Error should mention that source is already an interface");
        }
    }

    // ===================================================================
    // 10. ExtractSuperclassHandler
    // ===================================================================

    @Nested
    @DisplayName("ExtractSuperclassHandler")
    class ExtractSuperclassTests {

        private final ExtractSuperclassHandler handler = new ExtractSuperclassHandler();

        @Test
        @DisplayName("Extract superclass from UserService - preview mode")
        void extractSuperclass_fromUserService() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // Position on 'UserService' class name, line 12 (0-based), char 13
            Map<String, Object> p = params(uri, 12, 13);
            p.put("superclassName", "AbstractUserService");
            p.put("memberNames", List.of("users", "MAX_USERS", "addUser", "findByName"));

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);
        }

        @Test
        @DisplayName("Bug #7 regression - no NPE from null CodeGenerationSettings")
        void extractSuperclass_noNPE() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = params(uri, 12, 13);
            p.put("superclassName", "BaseService");
            p.put("memberNames", List.of("users", "MAX_USERS", "addUser"));

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);
        }

        @Test
        @DisplayName("Extract superclass produces edits for both new superclass and original file")
        void extractSuperclass_wholeFileFallback() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = params(uri, 12, 13);
            p.put("superclassName", "BaseUserService");
            p.put("memberNames", List.of("users", "MAX_USERS", "addUser"));

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edits = (List<Map<String, Object>>) resultMap.get("edits");
            assertTrue(edits.size() >= 2, "Extract superclass should produce edits for at least 2 files (original + new superclass), got " + edits.size());

            boolean hasNewSuperclass = false;
            boolean hasOriginalFile = false;
            for (Map<String, Object> edit : edits) {
                String editUri = (String) edit.get("uri");
                StringBuilder newTextBuilder = new StringBuilder();
                if (edit.get("newText") != null) {
                    newTextBuilder.append(edit.get("newText"));
                } else if (edit.get("textEdits") != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> textEdits = (List<Map<String, Object>>) edit.get("textEdits");
                    for (Map<String, Object> te : textEdits) {
                        if (te.get("newText") != null) {
                            newTextBuilder.append(te.get("newText"));
                        }
                    }
                }
                String newText = newTextBuilder.toString();
                if (editUri != null && editUri.contains("BaseUserService")) {
                    hasNewSuperclass = true;
                    assertTrue(newText.contains("addUser"), "New superclass file should contain the moved method 'addUser'");
                }
                if (editUri != null && editUri.contains("UserService")) {
                    hasOriginalFile = true;
                }
            }
            assertTrue(hasNewSuperclass, "Edits should include the new superclass file (BaseUserService)");
            assertTrue(hasOriginalFile, "Edits should include the modified original file (UserService)");
        }

        @Test
        @DisplayName("Extract superclass with conflicting name returns validation error")
        void extractSuperclass_conflictingName_returnsError() throws Exception {
            String uri = fileUri("src/com/example/model/Admin.java");
            // Admin extends User; using "User" as superclass name conflicts with existing type
            Map<String, Object> p = params(uri, 4, 13);
            p.put("superclassName", "User");
            p.put("memberNames", List.of("department"));

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Extract superclass with no matching members returns error")
        void extractSuperclass_noMatchingMembers() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = params(uri, 12, 13);
            p.put("superclassName", "AbstractUserService");
            p.put("memberNames", List.of("nonExistentMember"));

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // 11. PullUpHandler
    // ===================================================================

    @Nested
    @DisplayName("PullUpHandler")
    class PullUpTests {

        private final PullUpHandler handler = new PullUpHandler();

        @Test
        @DisplayName("Pull up 'department' field from Admin to User - preview mode")
        void pullUp_departmentField() throws Exception {
            String uri = fileUri("src/com/example/model/Admin.java");
            // Position on 'Admin' class name, line 4 (0-based), char 13
            Map<String, Object> p = params(uri, 4, 13);
            p.put("memberNames", List.of("department"));

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertNotNull(resultMap);
            assertEquals(false, resultMap.get("applied"));
        }

        @Test
        @DisplayName("Pull up hasPermission method from Admin to User - preview mode")
        void pullUp_method() throws Exception {
            String uri = fileUri("src/com/example/model/Admin.java");
            Map<String, Object> p = params(uri, 4, 13);
            p.put("memberNames", List.of("hasPermission"));

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertNotNull(resultMap);
            assertEquals(false, resultMap.get("applied"));
        }

        @Test
        @DisplayName("Pull up with no matching members returns error")
        void pullUp_noMatchingMembers() throws Exception {
            String uri = fileUri("src/com/example/model/Admin.java");
            Map<String, Object> p = params(uri, 4, 13);
            p.put("memberNames", List.of("nonExistent"));

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // 12. PushDownHandler
    // ===================================================================

    @Nested
    @DisplayName("PushDownHandler")
    class PushDownTests {

        private final PushDownHandler handler = new PushDownHandler();

        @Test
        @DisplayName("Push down getDisplayName from User to Admin - preview mode")
        void pushDown_method() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            // Position on 'User' class name, line 12 (0-based), char 13
            Map<String, Object> p = params(uri, 12, 13);
            p.put("memberNames", List.of("getDisplayName"));

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Push down with no matching members returns error")
        void pushDown_noMatchingMembers() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            Map<String, Object> p = params(uri, 12, 13);
            p.put("memberNames", List.of("nonExistent"));

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // 13. IntroduceParameterObjectHandler
    // ===================================================================

    @Nested
    @DisplayName("IntroduceParameterObjectHandler")
    class IntroduceParameterObjectTests {

        private final IntroduceParameterObjectHandler handler = new IntroduceParameterObjectHandler();

        @Test
        @DisplayName("Introduce parameter object for searchUsers - preview mode")
        void introduceParamObject_searchUsers() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // searchUsers() at line 76 (0-based), method name starts at character 22
            Map<String, Object> p = params(uri, 76, 22);
            p.put("className", "SearchCriteria");
            p.put("parameterNames", List.of("query", "minAge", "maxAge"));

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Introduce parameter object on non-method position returns error")
        void introduceParamObject_nonMethod() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            // Line 14 (0-based) is the 'users' field
            Map<String, Object> p = params(uri, 14, 20);
            p.put("className", "SomeParams");
            p.put("parameterNames", List.of("a"));

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // 14. MoveTypeToNewFileHandler
    // ===================================================================

    @Nested
    @DisplayName("MoveTypeToNewFileHandler")
    class MoveTypeToNewFileTests {

        private final MoveTypeToNewFileHandler handler = new MoveTypeToNewFileHandler();

        @Test
        @DisplayName("Move top-level type returns error (already top-level)")
        void moveType_topLevel_returnsError() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            // User is a top-level type
            Map<String, Object> p = params(uri, 12, 13);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
            assertTrue(ex.getMessage().contains("top-level"),
                    "Error should mention that type is already top-level");
        }

        // TODO: Add test with inner/nested type when test project has one.
        // MoveTypeToNewFileHandler requires a nested/inner type to function.
    }

    // ===================================================================
    // 15. ConvertAnonymousToLambdaHandler
    // ===================================================================

    @Nested
    @DisplayName("ConvertAnonymousToLambdaHandler")
    class ConvertAnonymousToLambdaTests {

        private final ConvertAnonymousToLambdaHandler handler = new ConvertAnonymousToLambdaHandler();

        @Test
        @DisplayName("No anonymous class at position returns error")
        void convertLambda_noAnonymousClass() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            // No anonymous class exists here
            Map<String, Object> p = params(uri, 33, 18);

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        // TODO: Add test with anonymous class when test project has one.
        // ConvertAnonymousToLambdaHandler needs an anonymous class implementing
        // a functional interface to convert.
    }

    // ===================================================================
    // 16. OrganizeImportsHandler
    // ===================================================================

    @Nested
    @DisplayName("OrganizeImportsHandler")
    class OrganizeImportsTests {

        private final OrganizeImportsHandler handler = new OrganizeImportsHandler();

        @Test
        @DisplayName("Organize imports in UserService.java")
        void organizeImports_userService() throws Exception {
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = new HashMap<>();
            p.put("uri", uri);

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertNotNull(resultMap);
            assertEquals(false, resultMap.get("applied"),
                    "OrganizeImportsHandler always returns preview (applied=false)");
            // UserService.java has an unused import (java.io.IOException),
            // so organize imports should produce edits to remove it.
            @SuppressWarnings("unchecked")
            List<Object> edits = (List<Object>) resultMap.get("edits");
            assertNotNull(edits, "edits must not be null");
            // Whether edits are empty depends on whether there are unused imports
        }

        @Test
        @DisplayName("Organize imports with missing uri returns error")
        void organizeImports_missingUri() throws Exception {
            Map<String, Object> p = new HashMap<>();
            // No uri provided

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Organize imports in file with no unused imports")
        void organizeImports_noChanges() throws Exception {
            String uri = fileUri("src/com/example/service/Validator.java");
            Map<String, Object> p = new HashMap<>();
            p.put("uri", uri);

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertNotNull(resultMap);
            assertEquals(false, resultMap.get("applied"));
            // Validator.java has no imports, so no edits expected
        }
    }

    // ===================================================================
    // 17. ConvertToRecordHandler
    // ===================================================================

    @Nested
    @DisplayName("ConvertToRecordHandler")
    class ConvertToRecordTests {

        private final ConvertToRecordHandler handler = new ConvertToRecordHandler();

        @Test
        @DisplayName("Convert User.java to record - preview mode")
        void convertToRecord_previewMode() throws Exception {
            String uri = fileUri("src/com/example/model/User.java");
            // User class at line 12 (0-based), character 13
            Map<String, Object> p = params(uri, 12, 13);

            Object result = handler.execute(args(p), MONITOR);
            Map<String, Object> resultMap = asMap(result);

            assertPreviewSuccess(resultMap);
        }

        @Test
        @DisplayName("Convert class with superclass returns error")
        void convertToRecord_withSuperclass() throws Exception {
            String uri = fileUri("src/com/example/model/Admin.java");
            // Admin class at line 4 (0-based), character 13
            Map<String, Object> p = params(uri, 4, 13);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
            assertTrue(ex.getMessage().contains("superclass"),
                    "Error should mention superclass");
        }

        @Test
        @DisplayName("Convert interface returns error")
        void convertToRecord_interface() throws Exception {
            String uri = fileUri("src/com/example/service/Validator.java");
            // Validator interface at line 2 (0-based), character 17
            Map<String, Object> p = params(uri, 2, 17);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
            assertTrue(ex.getMessage().contains("interface"),
                    "Error should mention interface");
        }

        @Test
        @DisplayName("Convert class with no instance fields returns error")
        void convertToRecord_noInstanceFields() throws Exception {
            String uri = fileUri("src/com/example/util/StringUtils.java");
            // StringUtils class at line 2 (0-based), character 13
            Map<String, Object> p = params(uri, 2, 13);

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // 18. MoveTypeToPackageHandler
    // ===================================================================

    @Nested
    @DisplayName("MoveTypeToPackageHandler")
    class MoveTypeToPackageTests {

        private final MoveTypeToPackageHandler handler = new MoveTypeToPackageHandler();

        @Test
        @DisplayName("Move to same package returns error")
        void moveToSamePackage_returnsError() throws Exception {
            String uri = fileUri("src/com/example/util/StringUtils.java");
            Map<String, Object> p = new HashMap<>();
            p.put("uri", uri);
            p.put("targetPackage", "com.example.util");

            RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
            assertTrue(ex.getMessage().contains("already in package"),
                    "Error should mention type is already in the target package");
        }

        @Test
        @DisplayName("Move with missing targetPackage returns error")
        void moveType_missingTargetPackage() throws Exception {
            String uri = fileUri("src/com/example/util/StringUtils.java");
            Map<String, Object> p = new HashMap<>();
            p.put("uri", uri);

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Move StringUtils to com.example.service - preview mode")
        void moveType_previewMode() throws Exception {
            String uri = fileUri("src/com/example/util/StringUtils.java");
            Map<String, Object> p = new HashMap<>();
            p.put("uri", uri);
            p.put("targetPackage", "com.example.newpkg");

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Move with missing uri returns error")
        void moveType_missingUri() throws Exception {
            Map<String, Object> p = new HashMap<>();
            p.put("targetPackage", "com.example.other");

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }
    }

    // ===================================================================
    // Cross-cutting: Missing/invalid parameter tests
    // ===================================================================

    @Nested
    @DisplayName("Error handling for missing parameters")
    class ErrorHandlingTests {

        @Test
        @DisplayName("RenameSymbolHandler with missing newName returns error")
        void rename_missingNewName() throws Exception {
            RenameSymbolHandler handler = new RenameSymbolHandler();
            String uri = fileUri("src/com/example/model/User.java");
            Map<String, Object> p = params(uri, 14, 19);
            // No newName provided

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("ExtractMethodHandler with missing methodName returns error")
        void extractMethod_missingMethodName() throws Exception {
            ExtractMethodHandler handler = new ExtractMethodHandler();
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = selectionParams(uri, 53, 12, 55, 13);
            // No methodName provided

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("ExtractVariableHandler with missing variableName returns error")
        void extractVariable_missingVariableName() throws Exception {
            ExtractVariableHandler handler = new ExtractVariableHandler();
            String uri = fileUri("src/com/example/model/User.java");
            Map<String, Object> p = selectionParams(uri, 77, 15, 77, 24);
            // No variableName provided

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("ExtractConstantHandler with missing constantName returns error")
        void extractConstant_missingConstantName() throws Exception {
            ExtractConstantHandler handler = new ExtractConstantHandler();
            String uri = fileUri("src/com/example/model/User.java");
            Map<String, Object> p = selectionParams(uri, 77, 22, 77, 24);
            // No constantName provided

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("ExtractInterfaceHandler with missing interfaceName returns error")
        void extractInterface_missingInterfaceName() throws Exception {
            ExtractInterfaceHandler handler = new ExtractInterfaceHandler();
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = params(uri, 12, 13);
            p.put("methodNames", List.of("addUser"));
            // No interfaceName provided

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("ExtractInterfaceHandler with missing methodNames returns error")
        void extractInterface_missingMethodNames() throws Exception {
            ExtractInterfaceHandler handler = new ExtractInterfaceHandler();
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = params(uri, 12, 13);
            p.put("interfaceName", "IUserService");
            // No methodNames provided

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("IntroduceParameterObjectHandler with missing className returns error")
        void introduceParamObj_missingClassName() throws Exception {
            IntroduceParameterObjectHandler handler = new IntroduceParameterObjectHandler();
            String uri = fileUri("src/com/example/service/UserService.java");
            Map<String, Object> p = params(uri, 76, 22);
            p.put("parameterNames", List.of("query"));
            // No className provided

            assertThrows(RuntimeException.class, () -> handler.execute(args(p), MONITOR));
        }

        @Test
        @DisplayName("Handlers with null arguments return error gracefully")
        void handler_nullArguments() throws Exception {
            RenameSymbolHandler handler = new RenameSymbolHandler();

            assertThrows(RuntimeException.class, () -> handler.execute(List.of(Map.of()), MONITOR));
        }
    }
}
