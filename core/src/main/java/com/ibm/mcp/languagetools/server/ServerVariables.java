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
package com.ibm.mcp.languagetools.server;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralized server variable definitions used in installer.json and server.json templates.
 * Variables use the $VARIABLE$ syntax (e.g. $SERVER_HOME$).
 */
public class ServerVariables {

    // e.g. $SERVER_HOME$ in installer.json / server.json
    private static final String SERVER_HOME = "SERVER_HOME";
    /**
     * Populate the given map with server variables resolved from the config.
     *
     * @param config the server configuration.
     * @param variables the map to populate.
     */
    public static void populate(ServerConfigBase config, Map<String, String> variables) {
        variables.put(SERVER_HOME, config.getServerHome().toString());
    }

    /**
     * Resolve all $VARIABLE$ placeholders in the given template using the server config.
     *
     * @param template the template string containing $VARIABLE$ placeholders.
     * @param config the server configuration.
     * @return the resolved string, or null if template is null.
     */
    public static String resolve(String template, ServerConfigBase config) {
        if (template == null || template.indexOf('$') == -1) {
            return template;
        }
        Map<String, String> variables = new HashMap<>();
        populate(config, variables);
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("$" + entry.getKey() + "$", entry.getValue());
        }
        return result;
    }
}
