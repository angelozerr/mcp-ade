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
package org.eclipse.mcp.ade.extensions.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GradleBuildSupportTest {

    private final GradleBuildSupport extractor = new GradleBuildSupport();

    // ---- canHandle ----

    @Test
    void canHandleBuildGradle(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("build.gradle"), "apply plugin: 'java'");
        assertTrue(extractor.canHandle(workspace));
    }

    @Test
    void canHandleBuildGradleKts(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("build.gradle.kts"), "plugins { java }");
        assertTrue(extractor.canHandle(workspace));
    }

    @Test
    void canHandleNoGradleFiles(@TempDir Path workspace) {
        assertFalse(extractor.canHandle(workspace));
    }

    @Test
    void canHandlePomXmlOnly(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        assertFalse(extractor.canHandle(workspace));
    }

    @Test
    void canHandleBothGradleFiles(@TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("build.gradle"), "");
        Files.writeString(workspace.resolve("build.gradle.kts"), "");
        assertTrue(extractor.canHandle(workspace));
    }

    // ---- findGradleExecutable ----

    @Test
    void findGradleExecutableWithWrapper(@TempDir Path workspace) throws IOException {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String wrapperName = isWindows ? "gradlew.bat" : "gradlew";
        Files.writeString(workspace.resolve(wrapperName), "#!/bin/sh\necho gradle");

        Path executable = extractor.findGradleExecutable(workspace);

        assertEquals(workspace.resolve(wrapperName), executable);
    }

    @Test
    void findGradleExecutableFallsBackToSystem(@TempDir Path workspace) {
        Path executable = extractor.findGradleExecutable(workspace);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertEquals(Path.of(isWindows ? "gradle.bat" : "gradle"), executable);
    }
}
