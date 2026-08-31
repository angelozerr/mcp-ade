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
package org.eclipse.mcp.ade.server;

public class ActivationCondition {

    private String fileExists;
    private String globPattern;
    private CommandCondition command;

    public String getFileExists() {
        return fileExists;
    }

    public void setFileExists(String fileExists) {
        this.fileExists = fileExists;
    }

    public String getGlobPattern() {
        return globPattern;
    }

    public void setGlobPattern(String globPattern) {
        this.globPattern = globPattern;
    }

    public CommandCondition getCommand() {
        return command;
    }

    public void setCommand(CommandCondition command) {
        this.command = command;
    }

    public static class CommandCondition {

        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
