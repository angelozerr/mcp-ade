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
package com.ibm.mcp.languagetools.dap.server;

import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.extension.Extension;
import com.ibm.mcp.languagetools.server.ServerDescriptorLoaderBase;
import com.ibm.mcp.languagetools.configuration.PathConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

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
    private static final String FIELD_DEBUG_SERVER_READY_PATTERN = "debugServerReadyPattern";
    private static final String FIELD_CONNECT_TIMEOUT = "connectTimeout";

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

        return jsonObject;
    }

}
