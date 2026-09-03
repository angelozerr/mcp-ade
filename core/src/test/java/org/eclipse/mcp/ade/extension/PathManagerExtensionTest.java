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

import org.eclipse.mcp.ade.PathManager;
import org.eclipse.mcp.ade.configuration.PathConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathManagerExtensionTest {

    private PathManager pathManager;

    @BeforeEach
    void setUp() throws Exception {
        pathManager = new PathManager();
        PathConfig pathConfig = new TestPathConfig(Path.of("/home/user"));
        Field field = PathManager.class.getDeclaredField("pathConfig");
        field.setAccessible(true);
        field.set(pathManager, pathConfig);
    }

    @Test
    void getExtensionsDir() {
        Path expected = Path.of("/home/user/.mcp-ade/extensions");
        assertEquals(expected, pathManager.getExtensionsDir());
    }

    @Test
    void getExtensionDir() {
        Path expected = Path.of("/home/user/.mcp-ade/extensions/jdtls");
        assertEquals(expected, pathManager.getExtensionDir("jdtls"));
    }

    @Test
    void getExtensionServerHome_lsp() {
        Path expected = Path.of("/home/user/.mcp-ade/extensions/jdtls/lsp/jdtls");
        assertEquals(expected, pathManager.getExtensionServerHome("jdtls", PathConfig.getLspDirName(), "jdtls"));
    }

    @Test
    void getExtensionServerHome_dap() {
        Path expected = Path.of("/home/user/.mcp-ade/extensions/java-debug/dap/java-debug");
        assertEquals(expected, pathManager.getExtensionServerHome("java-debug", PathConfig.getDapDirName(), "java-debug"));
    }

    @Test
    void getExtensionServerHome_multiServer() {
        Path expected = Path.of("/home/user/.mcp-ade/extensions/python-tools/lsp/pyright");
        assertEquals(expected, pathManager.getExtensionServerHome("python-tools", PathConfig.getLspDirName(), "pyright"));
    }

    private static class TestPathConfig extends PathConfig {
        private final Path root;

        TestPathConfig(Path root) {
            this.root = root;
        }

        @Override
        public Path getRootDir() {
            return root;
        }

    }
}
