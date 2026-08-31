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
package org.eclipse.mcp.ade.extensions.jdtls.build;

import org.eclipse.mcp.ade.progress.ProgressMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AbstractBuildSupportTest {

    private final AbstractBuildSupport buildSupport = new AbstractBuildSupport() {
        @Override protected String unixWrapperName() { return "test"; }
        @Override protected String windowsWrapperName() { return "test.bat"; }
        @Override protected String unixSystemName() { return "test"; }
        @Override protected String windowsSystemName() { return "test.bat"; }
        @Override protected String buildToolOptsVar() { return "TEST_OPTS"; }
        @Override public boolean canHandle(Path workspaceRoot) { return false; }
        @Override public ClasspathInfo extract(Path workspaceRoot, Path moduleDir, ProgressMonitor progress) { return null; }
    };

    @Test
    void detectSourceRootsStandardLayout(@TempDir Path moduleDir) throws IOException {
        Files.createDirectories(moduleDir.resolve("src/main/java"));
        Files.createDirectories(moduleDir.resolve("src/main/resources"));
        Files.createDirectories(moduleDir.resolve("src/test/java"));

        List<String> roots = buildSupport.detectSourceRoots(moduleDir);

        assertTrue(roots.contains("src/main/java"));
        assertTrue(roots.contains("src/main/resources"));
        assertTrue(roots.contains("src/test/java"));
    }

    @Test
    void detectSourceRootsFallbackToSrc(@TempDir Path moduleDir) throws IOException {
        Files.createDirectories(moduleDir.resolve("src"));

        List<String> roots = buildSupport.detectSourceRoots(moduleDir);

        assertEquals(List.of("src"), roots);
    }

    @Test
    void detectSourceRootsEmptyModule(@TempDir Path moduleDir) {
        List<String> roots = buildSupport.detectSourceRoots(moduleDir);

        assertTrue(roots.isEmpty());
    }
}
