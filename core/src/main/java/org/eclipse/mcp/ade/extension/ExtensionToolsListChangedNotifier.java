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

import io.quarkiverse.mcp.server.ToolManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;

/**
 * Listens to extension enable/disable events and sends the MCP
 * {@code notifications/tools/list_changed} notification to all connected clients.
 * <p>
 * This notification is part of the MCP specification (2025-06-18): when the server's
 * tool list changes, clients that declared {@code listChanged} capability should be
 * notified so they can re-request {@code tools/list}.
 * <p>
 * Since the Quarkus MCP Server {@link ToolManager} does not expose a public API
 * to trigger this notification (the {@code notifyConnections} method is {@code protected}
 * in {@code FeatureManagerBase}), this class uses Java reflection as a workaround.
 * <p>
 * TODO: Once Quarkus MCP Server provides a public {@code notifyToolsListChanged()} API,
 *       replace the reflection call with the proper API.
 *
 * @see ExtensionToolFilter
 */
@ApplicationScoped
public class ExtensionToolsListChangedNotifier implements ExtensionListener {

    private static final Logger LOG = Logger.getLogger(ExtensionToolsListChangedNotifier.class);

    @Inject
    ToolManager toolManager;

    @Inject
    ExtensionRegistry extensionRegistry;

    /**
     * The resolved {@code notifyConnections(McpMethod)} method obtained via reflection.
     * Cached at startup to avoid repeated reflection lookups.
     */
    private Method notifyConnectionsMethod;

    /**
     * The {@code McpMethod.NOTIFICATIONS_TOOLS_LIST_CHANGED} enum constant,
     * resolved via reflection since it is not part of the public API.
     */
    private Object toolsListChangedConstant;

    /**
     * Registers this notifier as an {@link ExtensionListener} on the {@link ExtensionRegistry}
     * at application startup, and resolves the reflection targets.
     */
    void onStart(@Observes StartupEvent ev) {
        extensionRegistry.addExtensionListener(this);
        resolveReflectionTargets();
    }

    /**
     * Resolves the {@code FeatureManagerBase.notifyConnections(McpMethod)} method
     * and the {@code McpMethod.NOTIFICATIONS_TOOLS_LIST_CHANGED} enum constant
     * via reflection. If resolution fails, a warning is logged and notifications
     * will be silently skipped.
     */
    private void resolveReflectionTargets() {
        try {
            // Resolve McpMethod enum and its NOTIFICATIONS_TOOLS_LIST_CHANGED constant
            Class<?> mcpMethodClass = Class.forName("io.quarkiverse.mcp.server.McpMethod");
            toolsListChangedConstant = Enum.valueOf(
                    mcpMethodClass.asSubclass(Enum.class), "NOTIFICATIONS_TOOLS_LIST_CHANGED");

            // Walk up from ToolManagerImpl to FeatureManagerBase to find the protected method
            Class<?> clazz = toolManager.getClass();
            while (clazz != null) {
                try {
                    notifyConnectionsMethod = clazz.getDeclaredMethod("notifyConnections", mcpMethodClass);
                    notifyConnectionsMethod.setAccessible(true);
                    LOG.debugf("Resolved notifyConnections on %s", clazz.getName());
                    break;
                } catch (NoSuchMethodException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (notifyConnectionsMethod == null) {
                LOG.warn("Could not find notifyConnections method in ToolManager hierarchy. "
                        + "tools/list_changed notifications will not be sent on extension enable/disable.");
            }
        } catch (Exception e) {
            LOG.warn("Failed to resolve reflection targets for tools/list_changed notification. "
                    + "Notifications will not be sent on extension enable/disable.", e);
        }
    }

    /**
     * Called when an extension is enabled (added back).
     * Only sends {@code notifications/tools/list_changed} if the extension
     * has custom tools (i.e., tools prefixed with {@code <extensionId>_}).
     */
    @Override
    public void onAdded(ExtensionAddedEvent event) {
        if (hasExtensionTools(event.getExtension().getId())) {
            notifyToolsListChanged();
        }
    }

    /**
     * Called when an extension is disabled (removed).
     * Only sends {@code notifications/tools/list_changed} if the extension
     * has custom tools (i.e., tools prefixed with {@code <extensionId>_}).
     */
    @Override
    public void onRemoved(ExtensionRemovedEvent event) {
        if (hasExtensionTools(event.getExtension().getId())) {
            notifyToolsListChanged();
        }
    }

    /**
     * Checks whether the given extension has any registered MCP tools.
     * An extension has tools if any tool name starts with {@code <extensionId>_}.
     *
     * @param extensionId the extension ID to check
     * @return {@code true} if at least one tool belongs to this extension
     */
    private boolean hasExtensionTools(String extensionId) {
        String prefix = extensionId + "_";
        for (ToolManager.ToolInfo tool : toolManager) {
            if (tool.name().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Invokes {@code FeatureManagerBase.notifyConnections(McpMethod.NOTIFICATIONS_TOOLS_LIST_CHANGED)}
     * via reflection to send the MCP notification to all connected clients.
     */
    private void notifyToolsListChanged() {
        if (notifyConnectionsMethod == null || toolsListChangedConstant == null) {
            return;
        }
        try {
            notifyConnectionsMethod.invoke(toolManager, toolsListChangedConstant);
            LOG.debug("Sent notifications/tools/list_changed to all connected clients");
        } catch (Exception e) {
            LOG.warn("Failed to send notifications/tools/list_changed", e);
        }
    }
}
