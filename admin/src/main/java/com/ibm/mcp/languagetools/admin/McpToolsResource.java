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

import com.ibm.mcp.languagetools.admin.dto.McpToolDTO;
import io.quarkiverse.mcp.server.ToolManager;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * REST API for listing registered MCP tools.
 */
@Path("/api/admin/mcp/tools")
@Produces(MediaType.APPLICATION_JSON)
public class McpToolsResource {

    @Inject
    ToolManager toolManager;

    @GET
    public List<McpToolDTO> getTools() {
        List<McpToolDTO> tools = new ArrayList<>();
        for (ToolManager.ToolInfo tool : toolManager) {
            List<McpToolDTO.McpToolArgumentDTO> args = new ArrayList<>();
            for (ToolManager.ToolArgument arg : tool.arguments()) {
                args.add(new McpToolDTO.McpToolArgumentDTO(
                        arg.name(),
                        arg.description(),
                        arg.required(),
                        toJsonSchemaType(arg.type())
                ));
            }
            String[] groupInfo = resolveGroup(tool);
            tools.add(new McpToolDTO(
                    tool.name(),
                    tool.description(),
                    groupInfo[0],
                    groupInfo[1],
                    tool.serverNames(),
                    args
            ));
        }
        return tools;
    }

    private static String[] resolveGroup(ToolManager.ToolInfo tool) {
        if (!tool.isMethod()) {
            return new String[]{"Other", null};
        }
        Method method = tool.method().orElse(null);
        if (method == null) {
            return new String[]{"Other", null};
        }
        String packageName = method.getDeclaringClass().getPackageName();
        String className = method.getDeclaringClass().getSimpleName();
        if (className.endsWith("Tools")) {
            className = className.substring(0, className.length() - 5);
        }

        // Determine group from package
        if (packageName.contains(".bsp.tools") || packageName.contains(".bsp.")) {
            return new String[]{"Build", toReadable(className.replaceFirst("^Bsp", ""))};
        }
        if (packageName.contains(".dap.tools") || packageName.contains(".dap.")) {
            return new String[]{"DAP", toReadable(className.replaceFirst("^Dap", ""))};
        }
        if (packageName.contains(".lsp.tools") || packageName.contains(".lsp.")) {
            return new String[]{"LSP", toReadable(className)};
        }
        if (packageName.contains(".jdtls.tools") || packageName.contains(".jdtls.")) {
            // Strip "Java" prefix for sub-group: "JavaRefactoring" -> "Refactoring"
            String sub = className.startsWith("Java") ? className.substring(4) : className;
            return new String[]{"Java", toReadable(sub)};
        }
        // Core tools package (RootsTools, WorkspaceTools, ExtensionTools)
        return new String[]{"Admin", toReadable(className)};
    }

    private static String toReadable(String camelCase) {
        if (camelCase == null || camelCase.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camelCase.length(); i++) {
            char c = camelCase.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && Character.isLowerCase(camelCase.charAt(i - 1))) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String toJsonSchemaType(Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isPrimitive()) {
                if (int.class.equals(clazz) || long.class.equals(clazz)
                        || short.class.equals(clazz) || byte.class.equals(clazz)) {
                    return "integer";
                } else if (double.class.equals(clazz) || float.class.equals(clazz)) {
                    return "number";
                } else if (boolean.class.equals(clazz)) {
                    return "boolean";
                }
            } else if (String.class.equals(clazz)) {
                return "string";
            } else if (Integer.class.equals(clazz) || Long.class.equals(clazz)
                    || Short.class.equals(clazz) || Byte.class.equals(clazz)) {
                return "integer";
            } else if (Double.class.equals(clazz) || Float.class.equals(clazz)
                    || Number.class.isAssignableFrom(clazz)) {
                return "number";
            } else if (Boolean.class.equals(clazz)) {
                return "boolean";
            } else if (clazz.isArray()) {
                return "array";
            }
            return "object";
        } else if (type instanceof ParameterizedType pt) {
            if (pt.getRawType() instanceof Class<?> clazz && Collection.class.isAssignableFrom(clazz)) {
                return "array";
            }
            return "object";
        } else if (type instanceof GenericArrayType) {
            return "array";
        }
        return "string";
    }
}
