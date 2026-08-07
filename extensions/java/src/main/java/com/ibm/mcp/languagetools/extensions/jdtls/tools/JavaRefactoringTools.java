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
 * MCP tools for Java refactoring via JDT.LS delegate command handlers.
 *
 * <p>Tools adapted from <a href="https://github.com/pzalutski-pixel/javalens-mcp">javalens-mcp</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
@ApplicationScoped
public class JavaRefactoringTools {

    @Inject
    JdtlsCommandExecutor executor;

    @Tool(name = "java_rename_symbol",
          description = "Rename a Java symbol across the entire project. Prefer over Edit for renaming "
                  + "-- handles all references across files, import updates, and type hierarchy.")
    public CompletableFuture<String> renameSymbol(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = "New name for the symbol") String newName,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        params.put("newName", newName);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.RENAME_SYMBOL, params, cancellation, progress);
    }

    @Tool(name = "java_organize_imports",
          description = "Organize imports in a Java file: remove unused and sort")
    public CompletableFuture<String> organizeImports(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = JavaToolArgDescriptions.URIS, required = false) java.util.List<String> uris,
            Cancellation cancellation,
            Progress progress) {
        java.util.List<String> resolvedUris = RefactoringHelper.resolveUris(uri, uris);
        if (resolvedUris.size() > 1) {
            return executor.executeBatchCommand(cwd, JdtlsCommands.ORGANIZE_IMPORTS, resolvedUris,
                    u -> Map.of("uri", u), cancellation, progress);
        }
        return executor.executeCommand(cwd, JdtlsCommands.ORGANIZE_IMPORTS,
                Map.of("uri", uri),
                cancellation, progress);
    }

    @Tool(name = "java_extract_method",
          description = "Extract a code selection into a new method")
    public CompletableFuture<String> extractMethod(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = "Start line of the selection (0-based)") int startLine,
            @ToolArg(description = "Start character of the selection (0-based)") int startCharacter,
            @ToolArg(description = "End line of the selection (0-based)") int endLine,
            @ToolArg(description = "End character of the selection (0-based)") int endCharacter,
            @ToolArg(description = "Name for the extracted method") String methodName,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.rangeParams(uri, startLine, startCharacter, endLine, endCharacter);
        params.put("methodName", methodName);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.EXTRACT_METHOD, params, cancellation, progress);
    }

    @Tool(name = "java_extract_variable",
          description = "Extract an expression into a local variable")
    public CompletableFuture<String> extractVariable(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = "Start line of the expression (0-based)") int startLine,
            @ToolArg(description = "Start character of the expression (0-based)") int startCharacter,
            @ToolArg(description = "End line of the expression (0-based)") int endLine,
            @ToolArg(description = "End character of the expression (0-based)") int endCharacter,
            @ToolArg(description = "Name for the new variable") String variableName,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.rangeParams(uri, startLine, startCharacter, endLine, endCharacter);
        params.put("variableName", variableName);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.EXTRACT_VARIABLE, params, cancellation, progress);
    }

    @Tool(name = "java_extract_constant",
          description = "Extract an expression into a static final constant field")
    public CompletableFuture<String> extractConstant(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = "Start line of the expression (0-based)") int startLine,
            @ToolArg(description = "Start character of the expression (0-based)") int startCharacter,
            @ToolArg(description = "End line of the expression (0-based)") int endLine,
            @ToolArg(description = "End character of the expression (0-based)") int endCharacter,
            @ToolArg(description = "Name for the constant (e.g., 'MAX_SIZE')") String constantName,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.rangeParams(uri, startLine, startCharacter, endLine, endCharacter);
        params.put("constantName", constantName);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.EXTRACT_CONSTANT, params, cancellation, progress);
    }

    @Tool(name = "java_extract_interface",
          description = "Extract an interface from a Java class with selected methods")
    public CompletableFuture<String> extractInterface(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = "Name for the new interface") String interfaceName,
            @ToolArg(description = "List of method names to include in the interface") List<String> methodNames,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        params.put("interfaceName", interfaceName);
        params.put("methodNames", methodNames);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.EXTRACT_INTERFACE, params, cancellation, progress);
    }

    @Tool(name = "java_extract_superclass",
          description = "Extract a superclass from a Java class with selected members")
    public CompletableFuture<String> extractSuperclass(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = "Name for the new superclass") String superclassName,
            @ToolArg(description = "List of member names (fields and methods) to pull into the superclass") List<String> memberNames,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        params.put("superclassName", superclassName);
        params.put("memberNames", memberNames);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.EXTRACT_SUPERCLASS, params, cancellation, progress);
    }

    @Tool(name = "java_inline_method",
          description = "Inline a method by replacing call sites with the method body")
    public CompletableFuture<String> inlineMethod(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.INLINE_METHOD, params, cancellation, progress);
    }

    @Tool(name = "java_inline_variable",
          description = "Inline a local variable by replacing usages with its initializer")
    public CompletableFuture<String> inlineVariable(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.INLINE_VARIABLE, params, cancellation, progress);
    }

    @Tool(name = "java_change_method_signature",
          description = "Change a method's signature and update all call sites")
    public CompletableFuture<String> changeMethodSignature(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = "New method name (optional, keep current if empty)") String newName,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        params.put("newName", newName);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.CHANGE_METHOD_SIGNATURE, params, cancellation, progress);
    }

    @Tool(name = "java_convert_anonymous_to_lambda",
          description = "Convert an anonymous class to a lambda expression")
    public CompletableFuture<String> convertAnonymousToLambda(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.CONVERT_ANONYMOUS_TO_LAMBDA, params, cancellation, progress);
    }

    @Tool(name = "java_encapsulate_field",
          description = "Encapsulate a field by generating getter/setter methods")
    public CompletableFuture<String> encapsulateField(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.ENCAPSULATE_FIELD, params, cancellation, progress);
    }

    @Tool(name = "java_introduce_parameter_object",
          description = "Bundle method parameters into a parameter object class")
    public CompletableFuture<String> introduceParameterObject(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = "Name for the new parameter object class") String className,
            @ToolArg(description = "List of parameter names to bundle into the object") List<String> parameterNames,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        params.put("className", className);
        params.put("parameterNames", parameterNames);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.INTRODUCE_PARAMETER_OBJECT, params, cancellation, progress);
    }

    @Tool(name = "java_move_type_to_new_file",
          description = "Move a nested/inner type to its own top-level file")
    public CompletableFuture<String> moveTypeToNewFile(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.MOVE_TYPE_TO_NEW_FILE, params, cancellation, progress);
    }

    @Tool(name = "java_pull_up",
          description = "Pull members up from a subclass into its superclass")
    public CompletableFuture<String> pullUp(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = "List of member names (fields and methods) to pull up") List<String> memberNames,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        params.put("memberNames", memberNames);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.PULL_UP, params, cancellation, progress);
    }

    @Tool(name = "java_push_down",
          description = "Push members down from a superclass into its subclasses")
    public CompletableFuture<String> pushDown(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = "List of member names (fields and methods) to push down") List<String> memberNames,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        params.put("memberNames", memberNames);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.PUSH_DOWN, params, cancellation, progress);
    }

    @Tool(name = "java_convert_to_record",
          description = "Convert a Java class to a record (Java 16+)")
    public CompletableFuture<String> convertToRecord(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.CONVERT_TO_RECORD, params, cancellation, progress);
    }

    @Tool(name = "java_move_type_to_package",
          description = "Move a top-level Java type to a different package")
    public CompletableFuture<String> moveTypeToPackage(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = "Target package name (e.g., 'com.example.common')") String targetPackage,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("uri", uri);
        params.put("targetPackage", targetPackage);
        RefactoringHelper.putApply(params, apply);
        return executor.executeCommand(cwd, JdtlsCommands.MOVE_TYPE_TO_PACKAGE, params, cancellation, progress);
    }
}
