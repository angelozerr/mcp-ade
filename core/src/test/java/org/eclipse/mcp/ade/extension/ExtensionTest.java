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

import org.eclipse.mcp.ade.lsp.server.LspServerConfig;
import org.eclipse.mcp.ade.dap.server.DapServerConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionTest {

    @Test
    void newExtension_isEmpty() {
        Extension ext = new Extension("test-ext", ServerConfigSource.USER, null);
        assertTrue(ext.isEmpty());
        assertEquals("test-ext", ext.getId());
        assertEquals(ServerConfigSource.USER, ext.getSource());
    }

    @Test
    void addLspServerConfig_notEmpty() {
        Extension ext = new Extension("test-ext", ServerConfigSource.BUNDLED, null);
        LspServerConfig config = new TestLspServerConfig("pyright", ext);
        ext.addLspServerConfig(config);

        assertFalse(ext.isEmpty());
        assertEquals(1, ext.getLspServerConfigs().size());
        assertNotNull(ext.getLspServerConfig("pyright"));
        assertNull(ext.getLspServerConfig("nonexistent"));
    }

    @Test
    void addDapServerConfig_notEmpty() {
        Extension ext = new Extension("test-ext", ServerConfigSource.USER, null);
        DapServerConfig config = new TestDapServerConfig("debugpy", ext);
        ext.addDapServerConfig(config);

        assertFalse(ext.isEmpty());
        assertEquals(1, ext.getDapServerConfigs().size());
        assertNotNull(ext.getDapServerConfig("debugpy"));
    }

    @Test
    void removeLspServerConfig_becomesEmpty() {
        Extension ext = new Extension("test-ext", ServerConfigSource.USER, null);
        ext.addLspServerConfig(new TestLspServerConfig("pyright", ext));

        assertFalse(ext.isEmpty());

        boolean removed = ext.removeLspServerConfig("pyright");
        assertTrue(removed);
        assertTrue(ext.isEmpty());
    }

    @Test
    void removeDapServerConfig_becomesEmpty() {
        Extension ext = new Extension("test-ext", ServerConfigSource.USER, null);
        ext.addDapServerConfig(new TestDapServerConfig("debugpy", ext));

        boolean removed = ext.removeDapServerConfig("debugpy");
        assertTrue(removed);
        assertTrue(ext.isEmpty());
    }

    @Test
    void removeNonexistent_returnsFalse() {
        Extension ext = new Extension("test-ext", ServerConfigSource.USER, null);
        assertFalse(ext.removeLspServerConfig("ghost"));
        assertFalse(ext.removeDapServerConfig("ghost"));
    }

    @Test
    void mixedServers_notEmptyUntilAllRemoved() {
        Extension ext = new Extension("python-tools", ServerConfigSource.USER, null);
        ext.addLspServerConfig(new TestLspServerConfig("pyright", ext));
        ext.addDapServerConfig(new TestDapServerConfig("debugpy", ext));

        assertFalse(ext.isEmpty());

        ext.removeLspServerConfig("pyright");
        assertFalse(ext.isEmpty());

        ext.removeDapServerConfig("debugpy");
        assertTrue(ext.isEmpty());
    }

    @Test
    void getSource_bundledVsUser() {
        Extension bundled = new Extension("jdtls", ServerConfigSource.BUNDLED, null);
        Extension user = new Extension("custom", ServerConfigSource.USER, null);

        assertEquals(ServerConfigSource.BUNDLED, bundled.getSource());
        assertEquals(ServerConfigSource.USER, user.getSource());
    }

    @Test
    void getLspServerConfigs_unmodifiable() {
        Extension ext = new Extension("test-ext", ServerConfigSource.USER, null);
        ext.addLspServerConfig(new TestLspServerConfig("pyright", ext));

        assertThrows(UnsupportedOperationException.class,
                () -> ext.getLspServerConfigs().add(new TestLspServerConfig("other", ext)));
    }

    @Test
    void getDapServerConfigs_unmodifiable() {
        Extension ext = new Extension("test-ext", ServerConfigSource.USER, null);
        ext.addDapServerConfig(new TestDapServerConfig("debugpy", ext));

        assertThrows(UnsupportedOperationException.class,
                () -> ext.getDapServerConfigs().add(new TestDapServerConfig("other", ext)));
    }

    // --- Profile tests ---

    @Test
    void profilesDefaultToEmpty() {
        Extension ext = new Extension("test", ServerConfigSource.USER, null);
        assertTrue(ext.getProfiles().isEmpty());
        assertFalse(ext.hasProfile("maven"));
        assertFalse(ext.hasAnyProfile(Set.of("maven", "npm")));
    }

    @Test
    void setProfiles_jdtlsSupportsMavenAndGradle() {
        Extension ext = new Extension("java", ServerConfigSource.BUNDLED, null);
        ext.setProfiles(List.of("maven", "gradle"));

        assertEquals(List.of("maven", "gradle"), ext.getProfiles());
        assertTrue(ext.hasProfile("maven"));
        assertTrue(ext.hasProfile("gradle"));
        assertFalse(ext.hasProfile("npm"));
    }

    @Test
    void setProfiles_javaLsOnlyMaven() {
        Extension ext = new Extension("java-ls", ServerConfigSource.BUNDLED, null);
        ext.setProfiles(List.of("maven"));

        assertTrue(ext.hasProfile("maven"));
        assertFalse(ext.hasProfile("gradle"),
                "java-ls should NOT support gradle");
    }

    @Test
    void hasAnyProfileMatchesIntersection() {
        Extension ext = new Extension("java", ServerConfigSource.BUNDLED, null);
        ext.setProfiles(List.of("maven", "gradle"));

        assertTrue(ext.hasAnyProfile(Set.of("maven")));
        assertTrue(ext.hasAnyProfile(Set.of("gradle")));
        assertTrue(ext.hasAnyProfile(Set.of("maven", "npm")));
        assertFalse(ext.hasAnyProfile(Set.of("npm", "cargo")));
        assertFalse(ext.hasAnyProfile(Set.of()));
    }

    @Test
    void hasAnyProfileSelectiveActivation() {
        Extension jdtls = new Extension("java", ServerConfigSource.BUNDLED, null);
        jdtls.setProfiles(List.of("maven", "gradle"));

        Extension javaLs = new Extension("java-ls", ServerConfigSource.BUNDLED, null);
        javaLs.setProfiles(List.of("maven"));

        Set<String> gradleWorkspace = Set.of("gradle");
        assertTrue(jdtls.hasAnyProfile(gradleWorkspace), "JDT.LS supports gradle");
        assertFalse(javaLs.hasAnyProfile(gradleWorkspace), "java-ls does NOT support gradle");

        Set<String> mavenWorkspace = Set.of("maven");
        assertTrue(jdtls.hasAnyProfile(mavenWorkspace), "JDT.LS supports maven");
        assertTrue(javaLs.hasAnyProfile(mavenWorkspace), "java-ls supports maven");
    }

    @Test
    void setProfilesNullDefaultsToEmpty() {
        Extension ext = new Extension("test", ServerConfigSource.USER, null);
        ext.setProfiles(List.of("maven"));
        ext.setProfiles(null);
        assertTrue(ext.getProfiles().isEmpty());
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
}
