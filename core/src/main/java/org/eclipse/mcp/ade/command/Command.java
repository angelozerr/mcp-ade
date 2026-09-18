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
package org.eclipse.mcp.ade.command;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a CLI command exposed by the {@code mcpade} CLI tool.
 * <p>
 * This annotation follows the same pattern as Quarkus MCP Server's {@code @Tool}
 * annotation, but exposes the method as a CLI command instead of an MCP tool.
 * <p>
 * Example usage:
 * <pre>
 * {@literal @}Command(domain = CommandDomain.LSP, action = "list",
 *          description = "List all configured LSP servers")
 * public List&lt;LspConfigDTO&gt; listConfigs() { ... }
 * </pre>
 * This generates the CLI command: {@code mcpade lsp list}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Command {

    /**
     * The domain this command belongs to (e.g. LSP, DAP, BSP, EXTENSION).
     */
    CommandDomain domain();

    /**
     * The action name (e.g. "list", "add", "remove", "enable", "disable").
     */
    String action();

    /**
     * Human-readable description of the command, used for {@code --help} output.
     */
    String description() default "";
}
