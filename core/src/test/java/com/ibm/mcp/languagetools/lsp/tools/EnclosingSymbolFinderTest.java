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
package com.ibm.mcp.languagetools.lsp.tools;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnclosingSymbolFinderTest {

    @Test
    void findEnclosingInSimpleSymbol() {
        var catalog = symbol("catalog", SymbolKind.Class, 0, 0, 10, 0);
        var symbols = List.of(right(catalog));

        DocumentSymbol result = EnclosingSymbolFinder.findEnclosing(symbols, pos(5, 3));
        assertNotNull(result);
        assertEquals("catalog", result.getName());
    }

    @Test
    void findEnclosingReturnsDeepest() {
        var title = symbol("title", SymbolKind.Field, 3, 4, 3, 30);
        var book = symbol("book", SymbolKind.Class, 2, 2, 5, 2, title);
        var catalog = symbol("catalog", SymbolKind.Class, 0, 0, 10, 0, book);
        var symbols = List.of(right(catalog));

        DocumentSymbol result = EnclosingSymbolFinder.findEnclosing(symbols, pos(3, 10));
        assertNotNull(result);
        assertEquals("title", result.getName());
    }

    @Test
    void findEnclosingReturnsParentWhenNotInChild() {
        var title = symbol("title", SymbolKind.Field, 3, 4, 3, 30);
        var book = symbol("book", SymbolKind.Class, 2, 2, 5, 2, title);
        var catalog = symbol("catalog", SymbolKind.Class, 0, 0, 10, 0, book);
        var symbols = List.of(right(catalog));

        DocumentSymbol result = EnclosingSymbolFinder.findEnclosing(symbols, pos(7, 0));
        assertNotNull(result);
        assertEquals("catalog", result.getName());
    }

    @Test
    void findEnclosingReturnsNullOutsideAll() {
        var catalog = symbol("catalog", SymbolKind.Class, 2, 0, 10, 0);
        var symbols = List.of(right(catalog));

        DocumentSymbol result = EnclosingSymbolFinder.findEnclosing(symbols, pos(0, 0));
        assertNull(result);
    }

    @Test
    void findEnclosingReturnsNullForEmptyList() {
        DocumentSymbol result = EnclosingSymbolFinder.findEnclosing(List.of(), pos(5, 0));
        assertNull(result);
    }

    @Test
    void findEnclosingReturnsNullForNull() {
        DocumentSymbol result = EnclosingSymbolFinder.findEnclosing(null, pos(5, 0));
        assertNull(result);
    }

    @Test
    void findEnclosingAtExactStart() {
        var method = symbol("process", SymbolKind.Method, 5, 4, 15, 4);
        var symbols = List.of(right(method));

        DocumentSymbol result = EnclosingSymbolFinder.findEnclosing(symbols, pos(5, 4));
        assertNotNull(result);
        assertEquals("process", result.getName());
    }

    @Test
    void findEnclosingAtExactEnd() {
        var method = symbol("process", SymbolKind.Method, 5, 4, 15, 4);
        var symbols = List.of(right(method));

        DocumentSymbol result = EnclosingSymbolFinder.findEnclosing(symbols, pos(15, 4));
        assertNotNull(result);
        assertEquals("process", result.getName());
    }

    @Test
    void findEnclosingMultipleTopLevelSymbols() {
        var classA = symbol("ClassA", SymbolKind.Class, 0, 0, 20, 0);
        var classB = symbol("ClassB", SymbolKind.Class, 22, 0, 40, 0);
        var symbols = List.of(right(classA), right(classB));

        DocumentSymbol result = EnclosingSymbolFinder.findEnclosing(symbols, pos(25, 5));
        assertNotNull(result);
        assertEquals("ClassB", result.getName());
    }

    @Test
    void containsPositionBeforeStartLine() {
        assertFalse(EnclosingSymbolFinder.containsPosition(range(5, 0, 10, 0), pos(4, 0)));
    }

    @Test
    void containsPositionAfterEndLine() {
        assertFalse(EnclosingSymbolFinder.containsPosition(range(5, 0, 10, 0), pos(11, 0)));
    }

    @Test
    void containsPositionSameStartLineBeforeChar() {
        assertFalse(EnclosingSymbolFinder.containsPosition(range(5, 10, 10, 0), pos(5, 5)));
    }

    @Test
    void containsPositionSameEndLineAfterChar() {
        assertFalse(EnclosingSymbolFinder.containsPosition(range(5, 0, 10, 10), pos(10, 15)));
    }

    // --- helpers ---

    private static Either<SymbolInformation, DocumentSymbol> right(DocumentSymbol sym) {
        return Either.forRight(sym);
    }

    private static Position pos(int line, int character) {
        return new Position(line, character);
    }

    private static Range range(int startLine, int startChar, int endLine, int endChar) {
        return new Range(new Position(startLine, startChar), new Position(endLine, endChar));
    }

    private static DocumentSymbol symbol(String name, SymbolKind kind,
                                         int startLine, int startChar, int endLine, int endChar,
                                         DocumentSymbol... children) {
        DocumentSymbol sym = new DocumentSymbol();
        sym.setName(name);
        sym.setKind(kind);
        sym.setRange(new Range(new Position(startLine, startChar), new Position(endLine, endChar)));
        sym.setSelectionRange(new Range(new Position(startLine, startChar),
                new Position(startLine, startChar + name.length())));
        if (children.length > 0) {
            sym.setChildren(List.of(children));
        }
        return sym;
    }
}
