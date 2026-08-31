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
package com.ibm.mcp.languagetools.admin;

import com.ibm.mcp.languagetools.admin.dto.ErrorResponse;
import com.ibm.mcp.languagetools.runtime.ApplicationEnvironment;
import com.ibm.mcp.languagetools.runtime.RuntimeRegistry;
import com.ibm.mcp.languagetools.utils.OSUtils;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST endpoint for application environment (PATH, env vars, terminal).
 */
@Path("/api/admin/environment")
@Produces(MediaType.APPLICATION_JSON)
public class EnvironmentAdminResource {

    @Inject
    RuntimeRegistry runtimeRegistry;

    @ConfigProperty(name = "mcp.admin.terminal.enabled", defaultValue = "true")
    boolean terminalEnabled;

    @GET
    @Path("/terminal-enabled")
    public Map<String, Object> isTerminalEnabled() {
        return Map.of("enabled", terminalEnabled);
    }

    @GET
    public Map<String, Object> getEnvironment() {
        ApplicationEnvironment env = runtimeRegistry.getApplicationEnvironment();
        Map<String, Object> result = new LinkedHashMap<>();

        List<Map<String, Object>> pathEntries = new ArrayList<>();
        for (ApplicationEnvironment.PathEntry entry : env.getPathEntries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("directory", entry.directory());
            m.put("source", entry.source());
            m.put("sourceType", entry.sourceType());
            m.put("exists", entry.exists());
            pathEntries.add(m);
        }
        result.put("path", pathEntries);

        List<Map<String, Object>> envEntries = new ArrayList<>();
        for (ApplicationEnvironment.EnvEntry entry : env.getEnvEntries().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", entry.name());
            m.put("value", entry.value());
            m.put("source", entry.source());
            m.put("sourceType", entry.sourceType());
            envEntries.add(m);
        }
        result.put("env", envEntries);

        return result;
    }

    @POST
    @Path("/exec")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response execCommand(Map<String, String> body) {
        if (!terminalEnabled) {
            return Response.status(403).entity(new ErrorResponse(
                    "Terminal is disabled. Set mcp.admin.terminal.enabled=true to enable.")).build();
        }

        String command = body.get("command");
        if (command == null || command.isBlank()) {
            return Response.status(400).entity(new ErrorResponse("Missing 'command' field")).build();
        }

        try {
            ProcessBuilder pb;
            if (OSUtils.isWindows()) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }
            pb.redirectErrorStream(true);

            ApplicationEnvironment env = runtimeRegistry.getApplicationEnvironment();
            pb.environment().put("PATH", env.getPath());
            for (ApplicationEnvironment.EnvEntry entry : env.getEnvEntries().values()) {
                pb.environment().put(entry.name(), entry.value());
            }

            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                }
            }

            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return Response.ok(Map.of(
                        "exitCode", -1,
                        "output", output + "\n[Command timed out after 10s]"
                )).build();
            }

            return Response.ok(Map.of(
                    "exitCode", process.exitValue(),
                    "output", output.toString()
            )).build();

        } catch (Exception e) {
            return Response.serverError().entity(new ErrorResponse("Failed to execute: " + e.getMessage())).build();
        }
    }
}
