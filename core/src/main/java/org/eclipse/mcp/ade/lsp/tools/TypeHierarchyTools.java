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
package org.eclipse.mcp.ade.lsp.tools;

import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.tools.params.FilePositionRequestParams;
import org.eclipse.mcp.ade.lsp.tools.strategies.TypeHierarchySubtypesStrategy;
import org.eclipse.mcp.ade.lsp.tools.strategies.TypeHierarchySupertypesStrategy;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.CompletableFuture;

@ApplicationScoped
public class TypeHierarchyTools {

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(name = "get_type_hierarchy_supertypes",
          description = "Get supertypes (parent classes/interfaces) for a type at a specific position. " +
                        "Returns the list of supertypes in the type hierarchy. " +
                        "Example: get_type_hierarchy_supertypes(cwd='/home/user/project', fileUri='file:///home/user/project/src/MyClass.java', line=5, character=15)" +
                        ToolArgDescriptions.OPEN_DOCUMENT_HINT)
    public CompletableFuture<String> getTypeHierarchySupertypes(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {

        FilePositionRequestParams params = new FilePositionRequestParams(cwd, uri, line, character);
        return requestExecutor.executeAsString(
                params,
                new TypeHierarchySupertypesStrategy(languageRegistry),
                cancellation,
                progress);
    }

    @Tool(name = "get_type_hierarchy_subtypes",
          description = "Get subtypes (child classes/implementations) for a type at a specific position. " +
                        "Returns the list of subtypes in the type hierarchy. " +
                        "Example: get_type_hierarchy_subtypes(cwd='/home/user/project', fileUri='file:///home/user/project/src/MyInterface.java', line=5, character=15)" +
                        ToolArgDescriptions.OPEN_DOCUMENT_HINT)
    public CompletableFuture<String> getTypeHierarchySubtypes(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            @ToolArg(description = ToolArgDescriptions.POSITION_LINE) int line,
            @ToolArg(description = ToolArgDescriptions.POSITION_CHARACTER) int character,
            @ToolArg(description = ToolArgDescriptions.CANCELLATION) Cancellation cancellation,
            Progress progress) {

        FilePositionRequestParams params = new FilePositionRequestParams(cwd, uri, line, character);
        return requestExecutor.executeAsString(
                params,
                new TypeHierarchySubtypesStrategy(languageRegistry),
                cancellation,
                progress);
    }
}
