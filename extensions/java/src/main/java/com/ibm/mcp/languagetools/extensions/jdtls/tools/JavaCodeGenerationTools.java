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
 * MCP tools for Java code generation via JDT.LS delegate command handlers.
 *
 * <p>Generates boilerplate code: getters/setters, constructors, toString, equals/hashCode.</p>
 */
@ApplicationScoped
public class JavaCodeGenerationTools {

    @Inject
    JdtlsCommandExecutor executor;

    @Tool(name = "java_generate_getters_setters",
          description = "Generate getter and/or setter methods for fields in a Java class")
    public CompletableFuture<String> generateGettersSetters(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = "Generate only getters (default true)", required = false) Boolean generateGetters,
            @ToolArg(description = "Generate only setters (default true)", required = false) Boolean generateSetters,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> args = RefactoringHelper.positionParams(uri, line, character);
        if (generateGetters != null) {
            args.put("generateGetters", generateGetters);
        }
        if (generateSetters != null) {
            args.put("generateSetters", generateSetters);
        }
        RefactoringHelper.putApply(args, apply);
        return executor.executeCommand(cwd, JdtlsCommands.GENERATE_GETTERS_SETTERS, args, cancellation, progress);
    }

    @Tool(name = "java_generate_constructor",
          description = "Generate a constructor for a Java class from its fields")
    public CompletableFuture<String> generateConstructor(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = "Also generate a default no-arg constructor (default false)", required = false) Boolean generateDefault,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> args = RefactoringHelper.positionParams(uri, line, character);
        if (generateDefault != null) {
            args.put("generateDefault", generateDefault);
        }
        RefactoringHelper.putApply(args, apply);
        return executor.executeCommand(cwd, JdtlsCommands.GENERATE_CONSTRUCTOR, args, cancellation, progress);
    }

    @Tool(name = "java_generate_to_string",
          description = "Generate a toString() method for a Java class")
    public CompletableFuture<String> generateToString(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> args = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putApply(args, apply);
        return executor.executeCommand(cwd, JdtlsCommands.GENERATE_TO_STRING, args, cancellation, progress);
    }

    @Tool(name = "java_generate_equals_hashcode",
          description = "Generate equals() and hashCode() methods for a Java class")
    public CompletableFuture<String> generateEqualsHashCode(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> args = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putApply(args, apply);
        return executor.executeCommand(cwd, JdtlsCommands.GENERATE_EQUALS_HASHCODE, args, cancellation, progress);
    }

    @Tool(name = "java_generate_delegate_methods",
          description = "Generate delegate methods for a field in a Java class")
    public CompletableFuture<String> generateDelegateMethods(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.APPLY, required = false) Boolean apply,
            Cancellation cancellation,
            Progress progress) {
        Map<String, Object> args = RefactoringHelper.positionParams(uri, line, character);
        RefactoringHelper.putApply(args, apply);
        return executor.executeCommand(cwd, JdtlsCommands.GENERATE_DELEGATE_METHODS, args, cancellation, progress);
    }
}
