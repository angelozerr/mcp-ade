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
package org.eclipse.mcp.ade.dap.server.resolve;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Declarative resolve step configuration loaded from server.json.
 *
 * <p>A resolve step calls an LSP command via {@code workspace/executeCommand}
 * (routed through the bind mechanism) and maps the result back into the
 * launch configuration.</p>
 *
 * <p>Example server.json entry:</p>
 * <pre>{@code
 * {
 *   "intellij.java.resolveClasspath": {
 *     "args": [{"uri": "${uri}"}],
 *     "returns": {
 *       "classPaths": "$classpath",
 *       "modulePaths": "$modulePath"
 *     }
 *   }
 * }
 * }</pre>
 */
public class ResolveStepConfig {

    private final String command;
    private final List<Object> args;
    private final Map<String, String> returns;
    private final boolean optional;

    public ResolveStepConfig(String command, List<Object> args, Map<String, String> returns, boolean optional) {
        this.command = command;
        this.args = args != null ? args : Collections.emptyList();
        this.returns = returns != null ? returns : Collections.emptyMap();
        this.optional = optional;
    }

    /**
     * The LSP command name to execute (e.g., "intellij.java.resolveClasspath").
     */
    public String getCommand() {
        return command;
    }

    /**
     * The arguments to pass to the command. May contain {@code ${variable}}
     * references that are resolved from the launch configuration.
     */
    public List<Object> getArgs() {
        return args;
    }

    /**
     * Maps result fields to launch configuration properties.
     * Key = target property name in launch config,
     * value = source reference prefixed with {@code $} (e.g., {@code $classpath},
     * {@code $[0]}, or {@code $} for the raw result).
     */
    public Map<String, String> getReturns() {
        return returns;
    }

    /**
     * If true, errors from this step are tolerated and execution continues.
     */
    public boolean isOptional() {
        return optional;
    }
}
