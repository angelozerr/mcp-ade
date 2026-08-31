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
package org.eclipse.mcp.ade.lsp.tools;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SymbolNameResolverTest {

    @Test
    void findBestMatchExactName() {
        var symbols = List.of(
                symbolInfo("book", "catalog", SymbolKind.Field, "file:///test.xml", 2, 5),
                symbolInfo("author", "catalog", SymbolKind.Field, "file:///test.xml", 5, 5)
        );

        SymbolInformation result = SymbolNameResolver.findBestMatch(symbols, "book");
        assertNotNull(result);
        assertEquals("book", result.getName());
        assertEquals(2, result.getLocation().getRange().getStart().getLine());
    }

    @Test
    void findBestMatchWithContainer() {
        var symbols = List.of(
                symbolInfo("myMethod", "ClassA", SymbolKind.Method, "file:///a.java", 10, 5),
                symbolInfo("myMethod", "ClassB", SymbolKind.Method, "file:///b.java", 20, 5)
        );

        SymbolInformation result = SymbolNameResolver.findBestMatch(symbols, "ClassB.myMethod");
        assertNotNull(result);
        assertEquals("myMethod", result.getName());
        assertEquals("ClassB", result.getContainerName());
        assertEquals(20, result.getLocation().getRange().getStart().getLine());
    }

    @Test
    void findBestMatchWithSlashSeparator() {
        var symbols = List.of(
                symbolInfo("myMethod", "ClassA", SymbolKind.Method, "file:///a.java", 10, 5),
                symbolInfo("myMethod", "ClassB", SymbolKind.Method, "file:///b.java", 20, 5)
        );

        SymbolInformation result = SymbolNameResolver.findBestMatch(symbols, "ClassA/myMethod");
        assertNotNull(result);
        assertEquals("ClassA", result.getContainerName());
        assertEquals(10, result.getLocation().getRange().getStart().getLine());
    }

    @Test
    void findBestMatchCaseInsensitive() {
        var symbols = List.of(
                symbolInfo("MyClass", null, SymbolKind.Class, "file:///test.java", 1, 0)
        );

        SymbolInformation result = SymbolNameResolver.findBestMatch(symbols, "myclass");
        assertNotNull(result);
        assertEquals("MyClass", result.getName());
    }

    @Test
    void findBestMatchPrefersExactOverCaseInsensitive() {
        var symbols = List.of(
                symbolInfo("myclass", null, SymbolKind.Class, "file:///a.java", 1, 0),
                symbolInfo("MyClass", null, SymbolKind.Class, "file:///b.java", 5, 0)
        );

        SymbolInformation result = SymbolNameResolver.findBestMatch(symbols, "myclass");
        assertNotNull(result);
        assertEquals(1, result.getLocation().getRange().getStart().getLine());
    }

    @Test
    void findBestMatchFallsBackToFirst() {
        var symbols = List.of(
                symbolInfo("something", null, SymbolKind.Field, "file:///test.xml", 3, 0),
                symbolInfo("other", null, SymbolKind.Field, "file:///test.xml", 7, 0)
        );

        SymbolInformation result = SymbolNameResolver.findBestMatch(symbols, "nonexistent");
        assertNotNull(result);
        assertEquals("something", result.getName());
    }

    @Test
    void findBestMatchReturnsNullForEmpty() {
        SymbolInformation result = SymbolNameResolver.findBestMatch(List.of(), "anything");
        assertNull(result);
    }

    @Test
    void findBestMatchSkipsSymbolsWithoutLocation() {
        var noLoc = new SymbolInformation();
        noLoc.setName("ghost");
        noLoc.setKind(SymbolKind.Variable);

        var withLoc = symbolInfo("ghost", null, SymbolKind.Variable, "file:///real.java", 1, 0);

        SymbolInformation result = SymbolNameResolver.findBestMatch(List.of(noLoc, withLoc), "ghost");
        assertNotNull(result);
        assertNotNull(result.getLocation());
        assertEquals(1, result.getLocation().getRange().getStart().getLine());
    }

    @Test
    void findBestMatchContainerPriorityOverNameOnly() {
        var symbols = List.of(
                symbolInfo("process", "ServiceA", SymbolKind.Method, "file:///a.java", 10, 0),
                symbolInfo("process", "ServiceB", SymbolKind.Method, "file:///b.java", 20, 0)
        );

        SymbolInformation withContainer = SymbolNameResolver.findBestMatch(symbols, "ServiceB.process");
        SymbolInformation withoutContainer = SymbolNameResolver.findBestMatch(symbols, "process");

        assertEquals(20, withContainer.getLocation().getRange().getStart().getLine());
        assertEquals(10, withoutContainer.getLocation().getRange().getStart().getLine());
    }

    // --- helpers ---

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
