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
package org.eclipse.mcp.ade.installer.task;

import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.installer.InstallerContext;
import org.eclipse.mcp.ade.utils.OSUtils;

public class ConfigureServerTask extends InstallerTask {
    private final String command;

    public ConfigureServerTask(String name, InstallerTask onSuccess, String command) {
        super(name, onSuccess);
        this.command = command;
    }

    @Override
    protected boolean run(InstallerContext context) {
        String resolvedCommand = context.resolveVariables(command);
        context.setVariable("SERVER_COMMAND", resolvedCommand);
        context.traceInfo("Server command configured: " + resolvedCommand);
        return true;
    }

    public String getCommand() {
        return command;
    }

    public static class Factory extends InstallerTaskFactoryBase {
        @Override
        public String getType() {
            return "configureServer";
        }

        @Override
        protected String getDefaultName() {
            return "Configure server";
        }

        @Override
        protected InstallerTask create(String name, InstallerTask onSuccess, JsonObject json) {
            String command = OSUtils.getStringFromOs(json, "command");
            return new ConfigureServerTask(name, onSuccess, command);
        }
    }
}
