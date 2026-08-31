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
package org.eclipse.mcp.ade.variable;

import org.eclipse.mcp.ade.installer.InstallableConfig;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable context for variable resolution.
 * Contains the data that varies per resolution call.
 */
public class VariableContext {

    private static final VariableContext EMPTY = new Builder().build();

    private final InstallableConfig serverConfig;
    private final Path workspaceFolder;
    private final Map<String, String> extraVariables;

    private VariableContext(Builder builder) {
        this.serverConfig = builder.serverConfig;
        this.workspaceFolder = builder.workspaceFolder;
        this.extraVariables = builder.extraVariables.isEmpty()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(builder.extraVariables));
    }

    public static VariableContext empty() {
        return EMPTY;
    }

    public InstallableConfig getServerConfig() {
        return serverConfig;
    }

    public Path getWorkspaceFolder() {
        return workspaceFolder;
    }

    public String getExtraVariable(String name) {
        return extraVariables.get(name);
    }

    public Map<String, String> getExtraVariables() {
        return extraVariables;
    }

    public static class Builder {
        private InstallableConfig serverConfig;
        private Path workspaceFolder;
        private final Map<String, String> extraVariables = new LinkedHashMap<>();

        public Builder serverConfig(InstallableConfig serverConfig) {
            this.serverConfig = serverConfig;
            return this;
        }

        public Builder workspaceFolder(Path workspaceFolder) {
            this.workspaceFolder = workspaceFolder;
            return this;
        }

        public Builder extraVariable(String key, String value) {
            this.extraVariables.put(key, value);
            return this;
        }

        public Builder extraVariables(Map<String, String> variables) {
            if (variables != null) {
                this.extraVariables.putAll(variables);
            }
            return this;
        }

        public VariableContext build() {
            return new VariableContext(this);
        }
    }
}
