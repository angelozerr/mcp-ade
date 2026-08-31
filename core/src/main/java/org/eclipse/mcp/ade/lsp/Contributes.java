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
package org.eclipse.mcp.ade.lsp;

import com.google.gson.JsonElement;

import java.util.HashMap;
import java.util.Map;

/**
 * Extension contributions (Eclipse plugin.xml-like system).
 * Allows servers to extend other servers with custom contributions.
 *
 * Structure:
 * {
 *   "contributes": {
 *     "jdtls": {
 *       "bundles": ["plugins/*.jar"],
 *       "bindRequest": ["microprofile/java/projectInfo", ...],
 *       "bindNotification": ["textDocument/didOpen", "textDocument/didChange", ...],
 *       "bindMode": "direct"
 *     },
 *     "microprofile": {
 *       "bundles": ["lib/*.jar"]
 *     }
 *   }
 * }
 *
 * Each server interprets its own contribution format by looking for contributes.{serverId}.
 *
 * bindRequest: Route requests from this server to the target server
 *   - Default mode: "executeCommand" (via workspace/executeCommand)
 *   - Can override with "bindMode": "direct" for direct method calls
 *
 * bindNotification: Route notifications from this server to the target server
 *   - Default mode: "direct" (direct method call)
 *   - Can override with "bindMode": "executeCommand" if needed
 */
public class Contributes {

    /**
     * Server-specific contributions.
     * Key = target server ID (e.g., "jdtls", "microprofile")
     * Value = contribution data (interpreted by that target server)
     *
     * Example:
     * "jdtls": {
     *   "bundles": ["plugins/bundle.jar"],
     *   "bindRequest": ["microprofile/java/projectInfo"]
     * }
     */
    private Map<String, JsonElement> contributions = new HashMap<>();

    public Map<String, JsonElement> getContributions() {
        return contributions;
    }

    public void setContributions(Map<String, JsonElement> contributions) {
        this.contributions = contributions;
    }

    /**
     * Get contribution for a specific server.
     */
    public JsonElement getContribution(String serverId) {
        return contributions != null ? contributions.get(serverId) : null;
    }

    /**
     * Check if there are contributions for a specific server.
     */
    public boolean hasContribution(String serverId) {
        return contributions != null && contributions.containsKey(serverId);
    }
}
