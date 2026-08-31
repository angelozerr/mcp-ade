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
package org.eclipse.mcp.ade.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IdeConfiguration} with SPI providers and strategies.
 */
class IdeConfigurationTest {

    private static final IdeConfigurationProvider VSCODE = new VsCodeConfigurationProvider();
    private static final IdeConfigurationProvider BOB = new BobConfigurationProvider();

    private void writeSettingsFile(Path dir, String json) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json);
    }

    // --- FIRST_FOUND ---

    @Test
    void firstFoundVsCode(@TempDir Path root) throws IOException {
        writeSettingsFile(root.resolve(".vscode"), """
                {"editor.fontSize": 14}
                """);

        var config = new IdeConfiguration(root,
                List.of(VSCODE, BOB),
                IdeConfigurationStrategy.FIRST_FOUND);

        assertEquals(14.0, config.get("editor.fontSize"));
    }

    @Test
    void firstFoundBob(@TempDir Path root) throws IOException {
        writeSettingsFile(root.resolve(".bob"), """
                {"bob.theme": "dark"}
                """);

        var config = new IdeConfiguration(root,
                List.of(VSCODE, BOB),
                IdeConfigurationStrategy.FIRST_FOUND);

        assertEquals("dark", config.get("bob.theme"));
    }

    @Test
    void firstFoundPriorityVsCodeFirst(@TempDir Path root) throws IOException {
        writeSettingsFile(root.resolve(".vscode"), """
                {"source": "vscode"}
                """);
        writeSettingsFile(root.resolve(".bob"), """
                {"source": "bob"}
                """);

        var config = new IdeConfiguration(root,
                List.of(VSCODE, BOB),
                IdeConfigurationStrategy.FIRST_FOUND);

        assertEquals("vscode", config.get("source"));
    }

    @Test
    void firstFoundPriorityBobFirst(@TempDir Path root) throws IOException {
        writeSettingsFile(root.resolve(".vscode"), """
                {"source": "vscode"}
                """);
        writeSettingsFile(root.resolve(".bob"), """
                {"source": "bob"}
                """);

        var config = new IdeConfiguration(root,
                List.of(BOB, VSCODE),
                IdeConfigurationStrategy.FIRST_FOUND);

        assertEquals("bob", config.get("source"));
    }

    // --- MERGE ---

    @Test
    void mergeStrategy(@TempDir Path root) throws IOException {
        writeSettingsFile(root.resolve(".vscode"), """
                {"shared": "from-vscode", "vscode.only": true}
                """);
        writeSettingsFile(root.resolve(".bob"), """
                {"shared": "from-bob", "bob.only": true}
                """);

        var config = new IdeConfiguration(root,
                List.of(VSCODE, BOB),
                IdeConfigurationStrategy.MERGE);

        // First provider wins on conflicts
        assertEquals("from-vscode", config.get("shared"));
        // Unique keys from both are present
        assertEquals(true, config.get("vscode.only"));
        assertEquals(true, config.get("bob.only"));
    }

    // --- No file ---

    @Test
    void noFileExists(@TempDir Path root) {
        var config = new IdeConfiguration(root,
                List.of(VSCODE, BOB),
                IdeConfigurationStrategy.FIRST_FOUND);

        assertTrue(config.getAll().isEmpty());
    }

    // --- Reload ---

    @Test
    void reload(@TempDir Path root) throws IOException {
        writeSettingsFile(root.resolve(".vscode"), """
                {"version": 1}
                """);

        var config = new IdeConfiguration(root,
                List.of(VSCODE),
                IdeConfigurationStrategy.FIRST_FOUND);

        assertEquals(1.0, config.get("version"));

        // Overwrite and reload
        Files.writeString(root.resolve(".vscode").resolve("settings.json"), """
                {"version": 2}
                """);
        config.reload();

        assertEquals(2.0, config.get("version"));
    }
}
