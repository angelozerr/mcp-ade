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
package org.eclipse.mcp.ade.configuration;

import org.eclipse.mcp.ade.workspace.VsCodeConfigurationProvider;
import org.eclipse.mcp.ade.workspace.IdeConfiguration;
import org.eclipse.mcp.ade.workspace.IdeConfigurationStrategy;
import org.eclipse.lsp4j.ConfigurationItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Configuration#find(ConfigurationItem)}.
 * Ported from lsp4ij SettingsHelper_findSettingsTest.
 */
class AbstractConfigurationFindSettingsTest {

    private static final String TEST_JSON = """
            {
                "mylsp": {
                    "myscalarsetting": "value",
                    "myobjectsettings": {
                        "subsettingA": 1,
                        "subsettingB": 2
                    }
                },
                "flat.scalar.value": "flat value",
                "flat.scalar.value2": "flat value2",
                "JimmerDTO.Classpath.FindBuilder": false,
                "JimmerDTO.Classpath.FindConfiguration": false
            }
            """;

    private Configuration createConfig(Path root, String json) throws IOException {
        Path dir = root.resolve(".vscode");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings.json"), json);
        return new IdeConfiguration(root,
                List.of(new VsCodeConfigurationProvider()),
                IdeConfigurationStrategy.FIRST_FOUND);
    }

    private static ConfigurationItem item(String section) {
        ConfigurationItem item = new ConfigurationItem();
        item.setSection(section);
        return item;
    }

    @Test
    void objectValue(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        Object result = config.find(item("mylsp"));
        assertInstanceOf(Map.class, result);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("value", map.get("myscalarsetting"));
        assertInstanceOf(Map.class, map.get("myobjectsettings"));
    }

    @Test
    void primitiveValue(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        assertEquals("value", config.find(item("mylsp.myscalarsetting")));
    }

    @Test
    void deepPrimitiveValue(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        assertEquals(1.0, config.find(item("mylsp.myobjectsettings.subsettingA")));
    }

    @Test
    void nonExistingValue(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        assertNull(config.find(item("mylsp.nonexistant")));
    }

    @Test
    void emptySettings(@TempDir Path root) throws IOException {
        var config = createConfig(root, "{}");
        assertNull(config.find(item("mylsp.myobjectsettings.subsettingA")));
    }

    @Test
    void flatScalarValue(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        assertEquals("flat value", config.find(item("flat.scalar.value")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void flatScalarPrefix(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        Object result = config.find(item("flat.scalar"));
        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(2, map.size());
        assertEquals("flat value", map.get("flat.scalar.value"));
        assertEquals("flat value2", map.get("flat.scalar.value2"));
    }

    @Test
    void flatScalarNonExisting(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        assertNull(config.find(item("flat.scalar.nonexistant")));
    }

    @Test
    void nullItem(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        assertNull(config.find((ConfigurationItem) null));
    }

    @Test
    void nullSection(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        assertNull(config.find(item(null)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jimmerDTO(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        Object result = config.find(item("JimmerDTO"));
        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(2, map.size());
        assertEquals(false, map.get("JimmerDTO.Classpath.FindBuilder"));
        assertEquals(false, map.get("JimmerDTO.Classpath.FindConfiguration"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jimmerDTOClasspath(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        Object result = config.find(item("JimmerDTO.Classpath"));
        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(2, map.size());
    }

    @Test
    void jimmerDTOClasspathFindBuilder(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        assertEquals(false, config.find(item("JimmerDTO.Classpath.FindBuilder")));
    }

    @Test
    void findMultipleItems(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        List<ConfigurationItem> items = List.of(
                item("mylsp.myscalarsetting"),
                item("flat.scalar.value"),
                item("mylsp.nonexistant")
        );
        List<Object> results = config.find(items);
        assertEquals(3, results.size());
        assertEquals("value", results.get(0));
        assertEquals("flat value", results.get(1));
        assertNull(results.get(2));
    }

    @Test
    void findEmptyList(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        List<Object> results = config.find(List.of());
        assertTrue(results.isEmpty());
    }

    @Test
    void findNullList(@TempDir Path root) throws IOException {
        var config = createConfig(root, TEST_JSON);
        List<Object> results = config.find((List<ConfigurationItem>) null);
        assertTrue(results.isEmpty());
    }

    @Test
    void julia(@TempDir Path root) throws IOException {
        String juliaJson = """
                {
                    "julia.lint.call": true,
                    "julia.inlayHints.static.enabled": true
                }
                """;
        var config = createConfig(root, juliaJson);
        assertEquals(true, config.find(item("julia.lint.call")));
        assertEquals(true, config.find(item("julia.inlayHints.static.enabled")));
    }
}
