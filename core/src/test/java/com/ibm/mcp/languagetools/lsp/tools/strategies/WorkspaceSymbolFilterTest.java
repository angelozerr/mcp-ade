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
package com.ibm.mcp.languagetools.lsp.tools.strategies;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceSymbolFilterTest {

    @Test
    void noFilterReturnsAll() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("MyClass", "com.example", SymbolKind.Class, "file:///src/main/java/MyClass.java", 1, 0),
                symbolInfo("myMethod", "MyClass", SymbolKind.Method, "file:///src/main/java/MyClass.java", 10, 4),
                symbolInfo("myField", "MyClass", SymbolKind.Field, "file:///src/main/java/MyClass.java", 3, 4)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, null, null, null, null);
        assertEquals(3, result.size());
    }

    @Test
    void emptyListReturnsEmpty() {
        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(
                Collections.emptyList(), "Class", "*.java", "com.example", 10);
        assertTrue(result.isEmpty());
    }

    @Test
    void filterByKindClass() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("MyClass", "com.example", SymbolKind.Class, "file:///src/main/java/MyClass.java", 1, 0),
                symbolInfo("myMethod", "MyClass", SymbolKind.Method, "file:///src/main/java/MyClass.java", 10, 4),
                symbolInfo("MyInterface", "com.example", SymbolKind.Interface, "file:///src/main/java/MyInterface.java", 1, 0)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, "Class", null, null, null);
        assertEquals(1, result.size());
        assertEquals("MyClass", result.get(0).getName());
    }

    @Test
    void filterByKindCaseInsensitive() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("myMethod", "MyClass", SymbolKind.Method, "file:///src/main/java/MyClass.java", 10, 4),
                symbolInfo("myField", "MyClass", SymbolKind.Field, "file:///src/main/java/MyClass.java", 3, 4)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, "method", null, null, null);
        assertEquals(1, result.size());
        assertEquals("myMethod", result.get(0).getName());
    }

    @Test
    void filterByKindMethod() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("MyClass", "com.example", SymbolKind.Class, "file:///src/main/java/MyClass.java", 1, 0),
                symbolInfo("doSomething", "MyClass", SymbolKind.Method, "file:///src/main/java/MyClass.java", 10, 4),
                symbolInfo("process", "MyClass", SymbolKind.Method, "file:///src/main/java/MyClass.java", 20, 4)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, "Method", null, null, null);
        assertEquals(2, result.size());
        assertEquals("doSomething", result.get(0).getName());
        assertEquals("process", result.get(1).getName());
    }

    @Test
    void filterByPathPatternJava() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("MyClass", null, SymbolKind.Class, "file:///src/main/java/MyClass.java", 1, 0),
                symbolInfo("myFunc", null, SymbolKind.Function, "file:///src/main/js/app.js", 1, 0),
                symbolInfo("OtherClass", null, SymbolKind.Class, "file:///src/main/java/OtherClass.java", 1, 0)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, null, "*.java", null, null);
        assertEquals(2, result.size());
        assertEquals("MyClass", result.get(0).getName());
        assertEquals("OtherClass", result.get(1).getName());
    }

    @Test
    void filterByContainerName() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("method1", "com.example.ServiceA", SymbolKind.Method, "file:///a.java", 10, 0),
                symbolInfo("method2", "com.example.ServiceB", SymbolKind.Method, "file:///b.java", 20, 0),
                symbolInfo("method3", "com.other.ServiceA", SymbolKind.Method, "file:///c.java", 30, 0)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, null, null, "ServiceA", null);
        assertEquals(2, result.size());
        assertEquals("method1", result.get(0).getName());
        assertEquals("method3", result.get(1).getName());
    }

    @Test
    void filterByContainerNameCaseInsensitive() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("method1", "MyClass", SymbolKind.Method, "file:///a.java", 10, 0),
                symbolInfo("method2", "OtherClass", SymbolKind.Method, "file:///b.java", 20, 0)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, null, null, "myclass", null);
        assertEquals(1, result.size());
        assertEquals("method1", result.get(0).getName());
    }

    @Test
    void filterByMaxResults() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("sym1", null, SymbolKind.Class, "file:///a.java", 1, 0),
                symbolInfo("sym2", null, SymbolKind.Class, "file:///b.java", 2, 0),
                symbolInfo("sym3", null, SymbolKind.Class, "file:///c.java", 3, 0),
                symbolInfo("sym4", null, SymbolKind.Class, "file:///d.java", 4, 0),
                symbolInfo("sym5", null, SymbolKind.Class, "file:///e.java", 5, 0)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, null, null, null, 3);
        assertEquals(3, result.size());
        assertEquals("sym1", result.get(0).getName());
        assertEquals("sym2", result.get(1).getName());
        assertEquals("sym3", result.get(2).getName());
    }

    @Test
    void combinedFilters() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("MyClass", "com.example", SymbolKind.Class, "file:///src/main/java/MyClass.java", 1, 0),
                symbolInfo("doWork", "com.example.MyClass", SymbolKind.Method, "file:///src/main/java/MyClass.java", 10, 4),
                symbolInfo("helper", "com.example.MyClass", SymbolKind.Method, "file:///src/main/java/Helper.java", 5, 4),
                symbolInfo("doWork", "com.other.OtherClass", SymbolKind.Method, "file:///src/main/java/OtherClass.java", 15, 4),
                symbolInfo("jsFunc", "module", SymbolKind.Method, "file:///src/main/js/app.js", 1, 0)
        );

        // Filter: Method kind + *.java path + "MyClass" container + max 1
        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(
                symbols, "Method", "*.java", "MyClass", 1);
        assertEquals(1, result.size());
        assertEquals("doWork", result.get(0).getName());
        assertTrue(result.get(0).getContainerName().contains("MyClass"));
    }

    @Test
    void filterByKindWithNullKindOnSymbol() {
        SymbolInformation noKind = new SymbolInformation();
        noKind.setName("unknown");
        noKind.setLocation(new Location("file:///test.java",
                new Range(new Position(0, 0), new Position(0, 7))));

        List<SymbolInformation> symbols = List.of(
                noKind,
                symbolInfo("MyClass", null, SymbolKind.Class, "file:///test.java", 1, 0)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, "Class", null, null, null);
        assertEquals(1, result.size());
        assertEquals("MyClass", result.get(0).getName());
    }

    @Test
    void filterByContainerNameSkipsNullContainer() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("MyClass", null, SymbolKind.Class, "file:///test.java", 1, 0),
                symbolInfo("method1", "MyClass", SymbolKind.Method, "file:///test.java", 10, 4)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, null, null, "MyClass", null);
        assertEquals(1, result.size());
        assertEquals("method1", result.get(0).getName());
    }

    @Test
    void filterByPathPatternSkipsNullLocation() {
        SymbolInformation noLoc = new SymbolInformation();
        noLoc.setName("ghost");
        noLoc.setKind(SymbolKind.Class);

        List<SymbolInformation> symbols = List.of(
                noLoc,
                symbolInfo("MyClass", null, SymbolKind.Class, "file:///src/main/java/MyClass.java", 1, 0)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, null, "*.java", null, null);
        assertEquals(1, result.size());
        assertEquals("MyClass", result.get(0).getName());
    }

    @Test
    void blankFilterValuesIgnored() {
        List<SymbolInformation> symbols = List.of(
                symbolInfo("MyClass", "com.example", SymbolKind.Class, "file:///test.java", 1, 0),
                symbolInfo("myMethod", "MyClass", SymbolKind.Method, "file:///test.java", 10, 4)
        );

        List<SymbolInformation> result = WorkspaceSymbolStrategy.filterSymbols(symbols, "  ", "  ", "  ", null);
        assertEquals(2, result.size());
    }

    // --- helper ---

    private static SymbolInformation symbolInfo(String name, String containerName,
                                                 SymbolKind kind, String uri,
                                                 int line, int character) {
        SymbolInformation info = new SymbolInformation();
        info.setName(name);
        info.setContainerName(containerName);
        info.setKind(kind);
        info.setLocation(new Location(uri,
                new Range(new Position(line, character), new Position(line, character + name.length()))));
        return info;
    }
}
