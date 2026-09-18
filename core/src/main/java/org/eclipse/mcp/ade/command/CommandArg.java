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
 * Marks a method parameter as a CLI command argument.
 * <p>
 * This annotation follows the same pattern as Quarkus MCP Server's {@code @ToolArg}
 * annotation, but maps to a CLI {@code --option} instead of an MCP tool argument.
 * <p>
 * Example usage:
 * <pre>
 * {@literal @}Command(domain = CommandDomain.EXTENSION, action = "add")
 * public Map&lt;String, Object&gt; addExtension(
 *     {@literal @}CommandArg(name = "id", description = "Extension ID") String extensionId,
 *     {@literal @}CommandArg(name = "source", description = "Path to source") String source) { ... }
 * </pre>
 * This generates: {@code mcpade extension add --id my-ext --source /path/to/ext}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CommandArg {

    /**
     * The name of the CLI option (e.g. "id" generates {@code --id}).
     * Defaults to the parameter name if empty.
     */
    String name() default "";

    /**
     * Human-readable description, used for {@code --help} output.
     */
    String description() default "";

    /**
     * Whether this argument is required. Defaults to true.
     */
    boolean required() default true;

    /**
     * Default value if the argument is not provided.
     */
    String defaultValue() default "";
}
