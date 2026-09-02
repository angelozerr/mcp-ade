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
package org.eclipse.mcp.ade.dap.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.dap.server.resolve.ResolveConfig;
import org.eclipse.mcp.ade.dap.server.resolve.ResolveStepConfig;
import org.eclipse.mcp.ade.extension.Extension;
import org.eclipse.mcp.ade.server.ServerDescriptorLoaderBase;
import org.eclipse.mcp.ade.configuration.PathConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads DAP server descriptors from JSON files.
 * Uses Gson exclusively for JSON parsing.
 */
@ApplicationScoped
public class DapServerDescriptorLoader extends ServerDescriptorLoaderBase<DapServerConfig> {

    private static final Logger LOG = Logger.getLogger(DapServerDescriptorLoader.class);

    // JSON field names
    private static final String FIELD_LAUNCH_METHOD = "launchMethod";
    private static final String FIELD_ATTACH = "attach";
    private static final String FIELD_RESOLVE = "resolve";
    private static final String FIELD_DEBUG_SERVER_READY_PATTERN = "debugServerReadyPattern";
    private static final String FIELD_CONNECT_TIMEOUT = "connectTimeout";

    // Resolve step reserved keys (everything else is the command name)
    private static final Set<String> RESOLVE_STEP_RESERVED_KEYS = Set.of("optional");

    public DapServerDescriptorLoader() {
        super();
    }

    @Override
    public String getRoot() {
        return PathConfig.getDapDirName();
    }

    @Override
    protected String getCommandFieldName() {
        return "launch";
    }

    @Override
    protected DapServerConfig createConfig(String serverId, Extension extension) {
        return new DapServerConfig(serverId, extension);
    }

    @Override
    protected JsonObject loadServer(String serverId, Path serverDir, DapServerConfig config) throws IOException {
        JsonObject jsonObject =  super.loadServer(serverId, serverDir, config);

        // Launch method (embedded mode)
        if (jsonObject.has(FIELD_LAUNCH_METHOD)) {
            config.setLaunchMethod(jsonObject.get(FIELD_LAUNCH_METHOD).getAsString());
        }

        // Attach configuration
        if (jsonObject.has(FIELD_ATTACH)) {
            Map<String, Object> attach = gson.fromJson(
                    jsonObject.get(FIELD_ATTACH),
                    Map.class
            );
            config.setAttach(attach);
        }

        // Debug server ready pattern
        if (jsonObject.has(FIELD_DEBUG_SERVER_READY_PATTERN)) {
            config.setDebugServerReadyPattern(jsonObject.get(FIELD_DEBUG_SERVER_READY_PATTERN).getAsString());
        }

        // Connect timeout
        if (jsonObject.has(FIELD_CONNECT_TIMEOUT)) {
            config.setConnectTimeout(jsonObject.get(FIELD_CONNECT_TIMEOUT).getAsInt());
        }

        // Resolve configuration
        if (jsonObject.has(FIELD_RESOLVE)) {
            config.setResolveConfig(parseResolveConfig(jsonObject.getAsJsonObject(FIELD_RESOLVE)));
        }

        return jsonObject;
    }

    /**
     * Parse the "resolve" section: { "launch": [ ... steps ... ] }
     */
    private ResolveConfig parseResolveConfig(JsonObject resolveJson) {
        Map<String, List<ResolveStepConfig>> steps = new LinkedHashMap<>();
        for (String requestType : resolveJson.keySet()) {
            JsonArray stepsArray = resolveJson.getAsJsonArray(requestType);
            if (stepsArray != null) {
                List<ResolveStepConfig> stepList = new ArrayList<>();
                for (JsonElement stepElement : stepsArray) {
                    if (stepElement.isJsonObject()) {
                        stepList.add(parseResolveStep(stepElement.getAsJsonObject()));
                    }
                }
                steps.put(requestType, stepList);
            }
        }
        return new ResolveConfig(steps);
    }

    /**
     * Parse a single resolve step. The command name is the key that is NOT
     * a reserved keyword ({@code optional}).
     *
     * <pre>{@code
     * {
     *   "optional": true,
     *   "intellij.java.resolveClasspath": {
     *     "args": [{"uri": "${uri}"}],
     *     "returns": {"classPaths": "$classpath"}
     *   }
     * }
     * }</pre>
     */
    private ResolveStepConfig parseResolveStep(JsonObject stepJson) {
        boolean optional = stepJson.has("optional") && stepJson.get("optional").getAsBoolean();

        // Find the command name: the key that is not a reserved keyword
        String command = null;
        JsonObject commandObj = null;
        for (String key : stepJson.keySet()) {
            if (!RESOLVE_STEP_RESERVED_KEYS.contains(key)) {
                command = key;
                commandObj = stepJson.getAsJsonObject(key);
                break;
            }
        }

        if (command == null || commandObj == null) {
            LOG.warnf("Resolve step has no command: %s", stepJson);
            return new ResolveStepConfig("unknown", List.of(), Map.of(), optional);
        }

        // Parse args
        List<Object> args = List.of();
        if (commandObj.has("args")) {
            args = gson.fromJson(commandObj.get("args"), List.class);
        }

        // Parse returns
        Map<String, String> returns = Map.of();
        if (commandObj.has("returns")) {
            JsonElement returnsElement = commandObj.get("returns");
            if (returnsElement.isJsonObject()) {
                returns = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : returnsElement.getAsJsonObject().entrySet()) {
                    returns.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }

        return new ResolveStepConfig(command, args, returns, optional);
    }

}
