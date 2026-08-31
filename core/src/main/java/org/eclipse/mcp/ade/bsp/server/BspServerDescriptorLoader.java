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
package org.eclipse.mcp.ade.bsp.server;

import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.configuration.PathConfig;
import org.eclipse.mcp.ade.extension.Extension;
import org.eclipse.mcp.ade.server.ServerDescriptorLoaderBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads BSP server descriptors from JSON files.
 * Uses Gson exclusively for JSON parsing.
 */
@ApplicationScoped
public class BspServerDescriptorLoader extends ServerDescriptorLoaderBase<BspServerConfig> {

    // JSON field names
    private static final String FIELD_INITIALIZATION_OPTIONS = "initializationOptions";
    private static final String FIELD_CONNECT_TIMEOUT = "connectTimeout";

    @Override
    public String getRoot() {
        return PathConfig.getBspDirName();
    }

    @Override
    protected String getCommandFieldName() {
        return "command";
    }

    @Override
    protected BspServerConfig createConfig(String serverId, Extension extension) {
        return new BspServerConfig(serverId, extension);
    }

    @Override
    protected JsonObject loadServer(String serverId, Path serverDir, BspServerConfig config) throws IOException {
        JsonObject jsonObject = super.loadServer(serverId, serverDir, config);

        // Initialization options
        if (jsonObject.has(FIELD_INITIALIZATION_OPTIONS)) {
            Map<String, Object> initOptions = gson.fromJson(
                    jsonObject.get(FIELD_INITIALIZATION_OPTIONS),
                    Map.class
            );
            config.setInitializationOptions(initOptions);
        }

        // Connect timeout
        if (jsonObject.has(FIELD_CONNECT_TIMEOUT)) {
            config.setConnectTimeout(jsonObject.get(FIELD_CONNECT_TIMEOUT).getAsInt());
        }

        return jsonObject;
    }

}
