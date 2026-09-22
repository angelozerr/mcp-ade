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
package org.eclipse.mcp.ade.extension;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Filters MCP tools based on extension enabled/disabled state.
 * <p>
 * This filter uses a naming convention: tools that belong to an extension
 * must be prefixed with {@code <extensionId>_} (e.g., {@code java_go_to_definition}
 * belongs to the {@code java} extension). When the owning extension is disabled,
 * the tool is hidden from {@code tools/list} and blocked from {@code tools/call}.
 * <p>
 * This filter is applied by Quarkus MCP Server both on:
 * <ul>
 *   <li>{@code tools/list} — disabled extension tools are not listed</li>
 *   <li>{@code tools/call} — disabled extension tools cannot be invoked</li>
 * </ul>
 *
 * @see ExtensionToolsListChangedNotifier
 * @see ExtensionRegistry#isExtensionEnabled(String)
 */
@Singleton
public class ExtensionToolFilter implements ToolFilter {

    @Inject
    ExtensionRegistry extensionRegistry;

    /**
     * Returns {@code true} if the tool should be visible/accessible.
     * <p>
     * For each registered extension, checks if the tool name starts with
     * {@code <extensionId>_}. If it does, the tool is only visible when
     * that extension is enabled. Tools that don't match any extension
     * prefix are always visible (core tools like {@code go_to_definition},
     * {@code list_extensions}, etc.).
     *
     * @param tool    the tool to check
     * @param context the filter context (connection, request info)
     * @return {@code true} if visible, {@code false} if hidden
     */
    @Override
    public boolean test(ToolInfo tool, FilterContext context) {
        String extensionId = extensionRegistry.getToolExtensionId(tool.name());
        if (extensionId != null) {
            return extensionRegistry.isExtensionEnabled(extensionId)
                    && extensionRegistry.isExtensionToolsEnabled(extensionId);
        }
        return true;
    }
}
