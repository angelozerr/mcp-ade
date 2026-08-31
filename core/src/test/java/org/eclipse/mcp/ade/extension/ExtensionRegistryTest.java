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

import org.eclipse.mcp.ade.configuration.ApplicationConfiguration;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionRegistryTest {

    private ExtensionRegistry registry;
    private Map<String, Extension> extensions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        registry = new ExtensionRegistry();

        // Inject a no-op ApplicationConfiguration to avoid NPE on persist calls
        Field appConfigField = ExtensionRegistry.class.getDeclaredField("applicationConfiguration");
        appConfigField.setAccessible(true);
        appConfigField.set(registry, new NoOpApplicationConfiguration());

        // Get access to internal extensions map
        Field extensionsField = ExtensionRegistry.class.getDeclaredField("extensions");
        extensionsField.setAccessible(true);
        extensions = (Map<String, Extension>) extensionsField.get(registry);
    }

    // ========== Enable / Disable extensions ==========

    @Test
    void newExtension_isEnabledByDefault() {
        Extension ext = createExtension("jdtls", ServerConfigSource.BUNDLED);
        extensions.put("jdtls", ext);

        assertTrue(registry.isExtensionEnabled("jdtls"));
    }

    @Test
    void disableExtension_becomesDisabled() {
        Extension ext = createExtension("jdtls", ServerConfigSource.BUNDLED);
        extensions.put("jdtls", ext);

        registry.disableExtension("jdtls");
        assertFalse(registry.isExtensionEnabled("jdtls"));
    }

    @Test
    void enableExtension_afterDisable() {
        Extension ext = createExtension("jdtls", ServerConfigSource.BUNDLED);
        extensions.put("jdtls", ext);

        registry.disableExtension("jdtls");
        assertFalse(registry.isExtensionEnabled("jdtls"));

        registry.enableExtension("jdtls");
        assertTrue(registry.isExtensionEnabled("jdtls"));
    }

    @Test
    void disableExtension_notFound_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.disableExtension("nonexistent"));
    }

    @Test
    void enableExtension_notFound_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.enableExtension("nonexistent"));
    }

    // ========== Enable / Disable servers ==========

    @Test
    void newServer_isEnabledByDefault() {
        assertTrue(registry.isServerEnabled("pyright"));
    }

    @Test
    void disableServer_becomesDisabled() {
        registry.disableServer("pyright");
        assertFalse(registry.isServerEnabled("pyright"));
    }

    @Test
    void enableServer_afterDisable() {
        registry.disableServer("pyright");
        registry.enableServer("pyright");
        assertTrue(registry.isServerEnabled("pyright"));
    }

    // ========== Disabled state persistence ==========

    @Test
    void setDisabledExtensions_setsState() {
        Extension ext1 = createExtension("ext1", ServerConfigSource.BUNDLED);
        Extension ext2 = createExtension("ext2", ServerConfigSource.BUNDLED);
        extensions.put("ext1", ext1);
        extensions.put("ext2", ext2);

        registry.setDisabledExtensions(List.of("ext1"));

        assertFalse(registry.isExtensionEnabled("ext1"));
        assertTrue(registry.isExtensionEnabled("ext2"));
    }

    @Test
    void setDisabledServers_setsState() {
        registry.setDisabledServers(List.of("pyright", "debugpy"));

        assertFalse(registry.isServerEnabled("pyright"));
        assertFalse(registry.isServerEnabled("debugpy"));
        assertTrue(registry.isServerEnabled("jdtls"));
    }

    @Test
    void getDisabledExtensions_returnsUnmodifiable() {
        Extension ext = createExtension("ext1", ServerConfigSource.BUNDLED);
        extensions.put("ext1", ext);
        registry.disableExtension("ext1");

        Set<String> disabled = registry.getDisabledExtensions();
        assertTrue(disabled.contains("ext1"));
        assertThrows(UnsupportedOperationException.class, () -> disabled.add("ext2"));
    }

    // ========== Queries: all vs enabled ==========

    @Test
    void getAllLspServerConfigs_includesDisabled() {
        Extension ext = createExtensionWithLsp("jdtls", ServerConfigSource.BUNDLED, "jdtls");
        extensions.put("jdtls", ext);

        registry.disableServer("jdtls");

        assertEquals(1, registry.getAllLspServerConfigs().size());
    }

    @Test
    void getEnabledLspServerConfigs_excludesDisabledServer() {
        Extension ext = createExtensionWithLsp("jdtls", ServerConfigSource.BUNDLED, "jdtls");
        extensions.put("jdtls", ext);

        assertEquals(1, registry.getEnabledLspServerConfigs().size());

        registry.disableServer("jdtls");
        assertEquals(0, registry.getEnabledLspServerConfigs().size());
    }

    @Test
    void getEnabledLspServerConfigs_excludesDisabledExtension() {
        Extension ext = createExtensionWithLsp("python-tools", ServerConfigSource.USER, "pyright");
        extensions.put("python-tools", ext);

        assertEquals(1, registry.getEnabledLspServerConfigs().size());

        registry.disableExtension("python-tools");
        assertEquals(0, registry.getEnabledLspServerConfigs().size());
    }

    @Test
    void getEnabledDapServerConfigs_excludesDisabledServer() {
        Extension ext = createExtensionWithDap("java-debug", ServerConfigSource.BUNDLED, "java-debug");
        extensions.put("java-debug", ext);

        assertEquals(1, registry.getEnabledDapServerConfigs().size());

        registry.disableServer("java-debug");
        assertEquals(0, registry.getEnabledDapServerConfigs().size());
    }

    @Test
    void getEnabledDapServerConfigs_excludesDisabledExtension() {
        Extension ext = createExtensionWithDap("python-tools", ServerConfigSource.USER, "debugpy");
        extensions.put("python-tools", ext);

        registry.disableExtension("python-tools");
        assertEquals(0, registry.getEnabledDapServerConfigs().size());
    }

    @Test
    void getAllDapServerConfigs_includesDisabled() {
        Extension ext = createExtensionWithDap("java-debug", ServerConfigSource.BUNDLED, "java-debug");
        extensions.put("java-debug", ext);

        registry.disableServer("java-debug");
        assertEquals(1, registry.getAllDapServerConfigs().size());
    }

    // ========== Lookups ==========

    @Test
    void getLspServerConfig_findsAcrossExtensions() {
        Extension ext1 = createExtensionWithLsp("ext1", ServerConfigSource.BUNDLED, "pyright");
        Extension ext2 = createExtensionWithLsp("ext2", ServerConfigSource.BUNDLED, "jdtls");
        extensions.put("ext1", ext1);
        extensions.put("ext2", ext2);

        assertNotNull(registry.getLspServerConfig("pyright"));
        assertNotNull(registry.getLspServerConfig("jdtls"));
        assertNull(registry.getLspServerConfig("nonexistent"));
    }

    @Test
    void getDapServerConfig_findsAcrossExtensions() {
        Extension ext1 = createExtensionWithDap("ext1", ServerConfigSource.BUNDLED, "java-debug");
        Extension ext2 = createExtensionWithDap("ext2", ServerConfigSource.BUNDLED, "debugpy");
        extensions.put("ext1", ext1);
        extensions.put("ext2", ext2);

        assertNotNull(registry.getDapServerConfig("java-debug"));
        assertNotNull(registry.getDapServerConfig("debugpy"));
        assertNull(registry.getDapServerConfig("nonexistent"));
    }

    @Test
    void getExtension_returnsNullForUnknown() {
        assertNull(registry.getExtension("nonexistent"));
    }

    @Test
    void getExtensions_returnsAll() {
        extensions.put("ext1", createExtension("ext1", ServerConfigSource.BUNDLED));
        extensions.put("ext2", createExtension("ext2", ServerConfigSource.USER));

        assertEquals(2, registry.getExtensions().size());
    }

    // ========== Remove bundled protection ==========

    @Test
    void removeExtension_bundled_throws() {
        Extension ext = createExtension("jdtls", ServerConfigSource.BUNDLED);
        extensions.put("jdtls", ext);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.removeExtension("jdtls"));
        assertTrue(ex.getMessage().contains("bundled"));
        assertTrue(ex.getMessage().contains("disable"));
    }

    @Test
    void removeExtension_notFound_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.removeExtension("nonexistent"));
    }

    @Test
    void removeLspServer_bundled_throws() {
        Extension ext = createExtensionWithLsp("jdtls", ServerConfigSource.BUNDLED, "jdtls");
        extensions.put("jdtls", ext);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.removeLspServer("jdtls"));
        assertTrue(ex.getMessage().contains("bundled"));
    }

    @Test
    void removeDapServer_bundled_throws() {
        Extension ext = createExtensionWithDap("java-debug", ServerConfigSource.BUNDLED, "java-debug");
        extensions.put("java-debug", ext);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.removeDapServer("java-debug"));
        assertTrue(ex.getMessage().contains("bundled"));
    }

    @Test
    void removeLspServer_notFound_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.removeLspServer("nonexistent"));
    }

    @Test
    void removeDapServer_notFound_throws() {
        assertThrows(IllegalArgumentException.class, () -> registry.removeDapServer("nonexistent"));
    }

    // ========== Listeners ==========

    @Test
    void extensionListener_onAdded_fires() {
        List<String> added = new ArrayList<>();
        registry.addExtensionListener(new ExtensionListener() {
            @Override
            public void onAdded(ExtensionAddedEvent event) {
                added.add(event.getExtension().getId());
            }
        });

        Extension ext = createExtension("test", ServerConfigSource.BUNDLED);
        extensions.put("test", ext);

        registry.disableExtension("test");
        registry.enableExtension("test");

        assertEquals(1, added.size());
        assertEquals("test", added.get(0));
    }

    @Test
    void extensionListener_onRemoved_fires() {
        List<String> removed = new ArrayList<>();
        registry.addExtensionListener(new ExtensionListener() {
            @Override
            public void onRemoved(ExtensionRemovedEvent event) {
                removed.add(event.getExtension().getId());
            }
        });

        Extension ext = createExtension("test", ServerConfigSource.BUNDLED);
        extensions.put("test", ext);

        registry.disableExtension("test");

        assertEquals(1, removed.size());
        assertEquals("test", removed.get(0));
    }

    // ========== Add server (programmatic API) ==========

    @Test
    void addLspServer_createsExtension() {
        Extension ext = createExtension("python-tools", ServerConfigSource.USER);
        extensions.put("python-tools", ext);

        LspServerConfig config = new TestLspServerConfig("pyright", ext);
        registry.addLspServer(config, "python-tools");

        assertNotNull(registry.getLspServerConfig("pyright"));
    }

    @Test
    void addLspServer_duplicateServerId_throws() {
        Extension ext = createExtensionWithLsp("ext1", ServerConfigSource.BUNDLED, "pyright");
        extensions.put("ext1", ext);

        Extension ext2 = createExtension("ext2", ServerConfigSource.USER);
        extensions.put("ext2", ext2);

        LspServerConfig config = new TestLspServerConfig("pyright", ext2);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.addLspServer(config, "ext2"));
        assertTrue(ex.getMessage().contains("already deployed"));
    }

    @Test
    void addDapServer_duplicateServerId_throws() {
        Extension ext = createExtensionWithDap("ext1", ServerConfigSource.BUNDLED, "java-debug");
        extensions.put("ext1", ext);

        Extension ext2 = createExtension("ext2", ServerConfigSource.USER);
        extensions.put("ext2", ext2);

        DapServerConfig config = new TestDapServerConfig("java-debug", ext2);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.addDapServer(config, "ext2"));
        assertTrue(ex.getMessage().contains("already deployed"));
    }

    // ========== Helpers ==========

    private Extension createExtension(String id, ServerConfigSource source) {
        return new Extension(id, source, null);
    }

    private Extension createExtensionWithLsp(String extensionId, ServerConfigSource source, String serverId) {
        Extension ext = new Extension(extensionId, source, null);
        ext.addLspServerConfig(new TestLspServerConfig(serverId, ext));
        return ext;
    }

    private Extension createExtensionWithDap(String extensionId, ServerConfigSource source, String serverId) {
        Extension ext = new Extension(extensionId, source, null);
        ext.addDapServerConfig(new TestDapServerConfig(serverId, ext));
        return ext;
    }

    private static class TestLspServerConfig extends LspServerConfig {
        TestLspServerConfig(String serverId, Extension extension) {
            super(serverId, Path.of("/tmp/test/" + serverId), extension);
        }
    }

    private static class TestDapServerConfig extends DapServerConfig {
        TestDapServerConfig(String serverId, Extension extension) {
            super(serverId, Path.of("/tmp/test/" + serverId), extension);
        }
    }

    private static class NoOpApplicationConfiguration extends ApplicationConfiguration {
        @Override
        public void setDisabledExtensionIds(List<String> ids) {
        }

        @Override
        public void setDisabledServerIds(List<String> ids) {
        }
    }
}
