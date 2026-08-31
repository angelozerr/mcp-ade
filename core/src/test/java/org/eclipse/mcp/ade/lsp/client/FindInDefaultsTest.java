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
package org.eclipse.mcp.ade.lsp.client;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindInDefaultsTest {

    private static Map<String, Object> createFlatDefaults() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("yaml.format.enable", true);
        defaults.put("yaml.format.singleQuote", false);
        defaults.put("yaml.format.printWidth", 80.0);
        defaults.put("yaml.validate", true);
        defaults.put("yaml.hover", true);
        defaults.put("yaml.completion", true);
        defaults.put("yaml.schemaStore.enable", true);
        defaults.put("other.setting", "value");
        return defaults;
    }

    @Test
    void directKeyMatch() {
        var defaults = createFlatDefaults();
        assertEquals(true, GenericLanguageClient.findInDefaults(defaults, "yaml.format.enable"));
    }

    @Test
    void directKeyMatchPrimitive() {
        var defaults = createFlatDefaults();
        assertEquals(80.0, GenericLanguageClient.findInDefaults(defaults, "yaml.format.printWidth"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sectionPrefixMatch() {
        var defaults = createFlatDefaults();
        Object result = GenericLanguageClient.findInDefaults(defaults, "yaml");
        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(7, map.size());
        assertEquals(true, map.get("yaml.format.enable"));
        assertEquals(false, map.get("yaml.format.singleQuote"));
        assertEquals(true, map.get("yaml.validate"));
        assertNull(map.get("other.setting"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedPrefixMatch() {
        var defaults = createFlatDefaults();
        Object result = GenericLanguageClient.findInDefaults(defaults, "yaml.format");
        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(3, map.size());
        assertEquals(true, map.get("yaml.format.enable"));
        assertEquals(false, map.get("yaml.format.singleQuote"));
        assertEquals(80.0, map.get("yaml.format.printWidth"));
    }

    @Test
    void nonExistingSection() {
        var defaults = createFlatDefaults();
        assertNull(GenericLanguageClient.findInDefaults(defaults, "nonexistent"));
    }

    @Test
    void nonExistingKey() {
        var defaults = createFlatDefaults();
        assertNull(GenericLanguageClient.findInDefaults(defaults, "yaml.format.nonexistent"));
    }

    @Test
    void emptyDefaults() {
        assertNull(GenericLanguageClient.findInDefaults(Map.of(), "yaml"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotMatchPartialSegment() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("yaml.format.enable", true);
        defaults.put("yamlExtra.setting", "value");
        Object result = GenericLanguageClient.findInDefaults(defaults, "yaml");
        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(1, map.size());
        assertNull(map.get("yamlExtra.setting"));
    }

    @Test
    void directKeyMatchWithNestedValue() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        Map<String, Object> nested = Map.of("enable", true, "singleQuote", false);
        defaults.put("yaml", nested);
        Object result = GenericLanguageClient.findInDefaults(defaults, "yaml");
        assertInstanceOf(Map.class, result);
        assertEquals(nested, result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void roslynStyleFlatKeys() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("dotnet.backgroundAnalysis.compilerDiagnosticsScope", "openFiles");
        defaults.put("dotnet.backgroundAnalysis.analyzerDiagnosticsScope", "openFiles");
        defaults.put("csharp.inlayHints.enableInlayHintsForTypes", false);

        assertEquals("openFiles",
                GenericLanguageClient.findInDefaults(defaults, "dotnet.backgroundAnalysis.compilerDiagnosticsScope"));

        Object dotnetResult = GenericLanguageClient.findInDefaults(defaults, "dotnet");
        assertInstanceOf(Map.class, dotnetResult);
        Map<String, Object> dotnetMap = (Map<String, Object>) dotnetResult;
        assertEquals(2, dotnetMap.size());

        Object csharpResult = GenericLanguageClient.findInDefaults(defaults, "csharp");
        assertInstanceOf(Map.class, csharpResult);
        Map<String, Object> csharpMap = (Map<String, Object>) csharpResult;
        assertEquals(1, csharpMap.size());
    }
}
