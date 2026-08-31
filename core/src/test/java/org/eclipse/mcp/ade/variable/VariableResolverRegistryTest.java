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

import org.eclipse.mcp.ade.server.ServerConfigBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariableResolverRegistryTest {

    private VariableResolverRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new VariableResolverRegistry();
        registry.addResolver(new ServerVariableResolver());
    }

    private static VariableContext contextWithServerHome(String serverHome) {
        ServerConfigBase config = new ServerConfigBase("test-server", Path.of(serverHome), null);
        return new VariableContext.Builder()
                .serverConfig(config)
                .build();
    }

    @Test
    void resolveNull() {
        assertNull(registry.resolve((String) null, VariableContext.empty()));
    }

    @Test
    void resolveNoVariables() {
        String input = "/some/plain/path";
        assertSame(input, registry.resolve(input, VariableContext.empty()));
    }

    @Test
    void resolveServerHome() {
        VariableContext ctx = contextWithServerHome("/opt/servers/lemminx");
        String result = registry.resolve("${serverHome}/bin/lemminx", ctx);
        assertEquals("/opt/servers/lemminx/bin/lemminx", result.replace("\\", "/"));
    }

    @Test
    void resolveMultipleOccurrences() {
        VariableContext ctx = contextWithServerHome("/opt/servers/lemminx");
        String result = registry.resolve("${serverHome}/bin:${serverHome}/lib", ctx);
        assertEquals("/opt/servers/lemminx/bin:/opt/servers/lemminx/lib", result.replace("\\", "/"));
    }

    @Test
    void resolveUnknownVariableKept() {
        VariableContext ctx = contextWithServerHome("/opt/servers/lemminx");
        String result = registry.resolve("${unknownVar}", ctx);
        assertEquals("${unknownVar}", result);
    }

    @Test
    void resolveExtraVariables() {
        VariableContext ctx = new VariableContext.Builder()
                .extraVariable("port", "12345")
                .build();
        String result = registry.resolve("server --port=${port}", ctx);
        assertEquals("server --port=12345", result);
    }

    @Test
    void resolveExtraVariablesFallback() {
        VariableContext ctx = new VariableContext.Builder()
                .serverConfig(new ServerConfigBase("test", Path.of("/opt/server"), null))
                .extraVariable("output.dir", "/tmp/download")
                .build();
        String result = registry.resolve("${serverHome}/${output.dir}/file.jar", ctx);
        assertEquals("/opt/server//tmp/download/file.jar", result.replace("\\", "/"));
    }

    @Test
    void resolveUserHome() {
        String result = registry.resolve("${userHome}/.julia", VariableContext.empty());
        String expected = System.getProperty("user.home") + "/.julia";
        assertEquals(expected, result);
    }

    @Test
    void resolveMcpHome() {
        VariableContext ctx = contextWithServerHome("/mcp/lsp/jdtls");
        String result = registry.resolve("${mcpHome}/.cache", ctx);
        assertEquals("/mcp/.cache", result.replace("\\", "/"));
    }

    @Test
    void resolveWorkspaceFolder() {
        VariableContext ctx = new VariableContext.Builder()
                .workspaceFolder(Path.of("/projects/myapp"))
                .build();
        String result = registry.resolve("${workspaceFolder}/src", ctx);
        assertEquals("/projects/myapp/src", result.replace("\\", "/"));
    }

    @Test
    void resolveWorkspaceRootAlias() {
        VariableContext ctx = new VariableContext.Builder()
                .workspaceFolder(Path.of("/projects/myapp"))
                .build();
        String result = registry.resolve("${workspaceRoot}/src", ctx);
        assertEquals("/projects/myapp/src", result.replace("\\", "/"));
    }

    @Test
    void resolveMapWithStrings() {
        VariableContext ctx = new VariableContext.Builder()
                .workspaceFolder(Path.of("/projects/myapp"))
                .build();
        Map<String, Object> input = Map.of(
                "cwd", "${workspaceFolder}",
                "port", 8080,
                "args", List.of("--dir=${workspaceFolder}/src", "--verbose")
        );
        Map<String, Object> result = registry.resolve(input, ctx);
        assertEquals("/projects/myapp", ((String) result.get("cwd")).replace("\\", "/"));
        assertEquals(8080, result.get("port"));
        @SuppressWarnings("unchecked")
        List<String> args = (List<String>) result.get("args");
        assertEquals("/projects/myapp/src", args.get(0).replace("\\", "/").replace("--dir=", ""));
        assertEquals("--verbose", args.get(1));
    }

    @Test
    void resolveServerHomeWithoutConfig() {
        String result = registry.resolve("${serverHome}/bin", VariableContext.empty());
        assertEquals("${serverHome}/bin", result);
    }
}
