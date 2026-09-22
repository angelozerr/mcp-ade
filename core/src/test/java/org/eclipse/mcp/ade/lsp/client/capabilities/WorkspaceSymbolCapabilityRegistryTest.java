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
package org.eclipse.mcp.ade.lsp.client.capabilities;

import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.WorkspaceSymbolOptions;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceSymbolCapabilityRegistryTest {

    @Test
    void notSupportedByDefault() {
        var registry = new WorkspaceSymbolCapabilityRegistry();
        assertFalse(registry.isWorkspaceSymbolSupported());
    }

    @Test
    void supportedWhenProviderIsTrue() {
        var registry = new WorkspaceSymbolCapabilityRegistry();
        ServerCapabilities caps = new ServerCapabilities();
        caps.setWorkspaceSymbolProvider(Either.forLeft(true));
        registry.setServerCapabilities(caps);
        assertTrue(registry.isWorkspaceSymbolSupported());
    }

    @Test
    void supportedWhenProviderIsOptions() {
        var registry = new WorkspaceSymbolCapabilityRegistry();
        ServerCapabilities caps = new ServerCapabilities();
        caps.setWorkspaceSymbolProvider(Either.forRight(new WorkspaceSymbolOptions()));
        registry.setServerCapabilities(caps);
        assertTrue(registry.isWorkspaceSymbolSupported());
    }

    @Test
    void notSupportedWhenProviderIsNull() {
        var registry = new WorkspaceSymbolCapabilityRegistry();
        ServerCapabilities caps = new ServerCapabilities();
        registry.setServerCapabilities(caps);
        assertFalse(registry.isWorkspaceSymbolSupported());
    }

    @Test
    void notSupportedWhenProviderIsFalse() {
        var registry = new WorkspaceSymbolCapabilityRegistry();
        ServerCapabilities caps = new ServerCapabilities();
        caps.setWorkspaceSymbolProvider(Either.forLeft(false));
        registry.setServerCapabilities(caps);
        assertFalse(registry.isWorkspaceSymbolSupported());
    }

    @Test
    void supportedWhenDynamicallyRegistered() {
        var registry = new WorkspaceSymbolCapabilityRegistry();
        registry.setDynamicallyRegistered(true);
        assertTrue(registry.isWorkspaceSymbolSupported());
    }

    @Test
    void dynamicRegistrationOverridesNullProvider() {
        var registry = new WorkspaceSymbolCapabilityRegistry();
        ServerCapabilities caps = new ServerCapabilities();
        registry.setServerCapabilities(caps);
        registry.setDynamicallyRegistered(true);
        assertTrue(registry.isWorkspaceSymbolSupported());
    }

    @Test
    void unregisterDynamicFallsBackToServerCapabilities() {
        var registry = new WorkspaceSymbolCapabilityRegistry();
        registry.setDynamicallyRegistered(true);
        assertTrue(registry.isWorkspaceSymbolSupported());

        registry.setDynamicallyRegistered(false);
        assertFalse(registry.isWorkspaceSymbolSupported());
    }

    @Test
    void unregisterDynamicKeepsServerCapabilitySupport() {
        var registry = new WorkspaceSymbolCapabilityRegistry();
        ServerCapabilities caps = new ServerCapabilities();
        caps.setWorkspaceSymbolProvider(Either.forLeft(true));
        registry.setServerCapabilities(caps);
        registry.setDynamicallyRegistered(true);
        assertTrue(registry.isWorkspaceSymbolSupported());

        registry.setDynamicallyRegistered(false);
        assertTrue(registry.isWorkspaceSymbolSupported());
    }
}
