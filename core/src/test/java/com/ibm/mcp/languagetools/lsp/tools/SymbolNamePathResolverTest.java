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

import com.ibm.mcp.languagetools.tools.ToolException;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SymbolNamePathResolverTest {

    @Test
    void resolveSimpleName() {
        var symbols = List.of(right(symbol("catalog", SymbolKind.Class, 0, 0, 10, 0)));
        DocumentSymbol result = SymbolNamePathResolver.resolve(symbols, "catalog");
        assertEquals("catalog", result.getName());
    }

    @Test
    void resolveNestedPath() {
        var book = symbol("book", SymbolKind.Field, 2, 0, 5, 0);
        var catalog = symbol("catalog", SymbolKind.Class, 0, 0, 10, 0, book);
        var symbols = List.of(right(catalog));

        DocumentSymbol result = SymbolNamePathResolver.resolve(symbols, "catalog/book");
        assertEquals("book", result.getName());
        assertEquals(2, result.getRange().getStart().getLine());
    }

    @Test
    void resolveWithIndex() {
        var book1 = symbol("book", SymbolKind.Field, 2, 0, 5, 0);
        var book2 = symbol("book", SymbolKind.Field, 6, 0, 9, 0);
        var catalog = symbol("catalog", SymbolKind.Class, 0, 0, 10, 0, book1, book2);
        var symbols = List.of(right(catalog));

        DocumentSymbol first = SymbolNamePathResolver.resolve(symbols, "catalog/book[0]");
        assertEquals(2, first.getRange().getStart().getLine());

        DocumentSymbol second = SymbolNamePathResolver.resolve(symbols, "catalog/book[1]");
        assertEquals(6, second.getRange().getStart().getLine());
    }

    @Test
    void resolveAmbiguousThrows() {
        var book1 = symbol("book", SymbolKind.Field, 2, 0, 5, 0);
        var book2 = symbol("book", SymbolKind.Field, 6, 0, 9, 0);
        var catalog = symbol("catalog", SymbolKind.Class, 0, 0, 10, 0, book1, book2);
        var symbols = List.of(right(catalog));

        ToolException ex = assertThrows(ToolException.class,
                () -> SymbolNamePathResolver.resolve(symbols, "catalog/book"));
        assertTrue(ex.getMessage().contains("Ambiguous"));
        assertTrue(ex.getMessage().contains("[0]"));
    }

    @Test
    void resolveNotFoundThrows() {
        var symbols = List.of(right(symbol("catalog", SymbolKind.Class, 0, 0, 10, 0)));

        ToolException ex = assertThrows(ToolException.class,
                () -> SymbolNamePathResolver.resolve(symbols, "nonexistent"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void resolveEmptySymbolsThrows() {
        List<Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>> symbols = List.of();

        ToolException ex = assertThrows(ToolException.class,
                () -> SymbolNamePathResolver.resolve(symbols, "anything"));
        assertTrue(ex.getMessage().contains("No document symbols"));
    }

    @Test
    void resolveDeepPath() {
        var title = symbol("title", SymbolKind.Field, 3, 0, 3, 30);
        var book = symbol("book", SymbolKind.Field, 2, 0, 5, 0, title);
        var catalog = symbol("catalog", SymbolKind.Class, 0, 0, 10, 0, book);
        var symbols = List.of(right(catalog));

        DocumentSymbol result = SymbolNamePathResolver.resolve(symbols, "catalog/book/title");
        assertEquals("title", result.getName());
        assertEquals(3, result.getRange().getStart().getLine());
    }

    @Test
    void resolveFallbackRecursiveSearch() {
        var title = symbol("title", SymbolKind.Field, 3, 0, 3, 30);
        var book = symbol("book", SymbolKind.Field, 2, 0, 5, 0, title);
        var catalog = symbol("catalog", SymbolKind.Class, 0, 0, 10, 0, book);
        var symbols = List.of(right(catalog));

        // "title" is not a top-level symbol, but recursive search finds it
        DocumentSymbol result = SymbolNamePathResolver.resolve(symbols, "title");
        assertEquals("title", result.getName());
    }

    @Test
    void resolveIndexOutOfBoundsThrows() {
        var book = symbol("book", SymbolKind.Field, 2, 0, 5, 0);
        var catalog = symbol("catalog", SymbolKind.Class, 0, 0, 10, 0, book);
        var symbols = List.of(right(catalog));

        ToolException ex = assertThrows(ToolException.class,
                () -> SymbolNamePathResolver.resolve(symbols, "catalog/book[5]"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    // --- helpers ---

    private static Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol> right(DocumentSymbol sym) {
        return Either.forRight(sym);
    }

    private static DocumentSymbol symbol(String name, SymbolKind kind,
                                         int startLine, int startChar, int endLine, int endChar,
                                         DocumentSymbol... children) {
        DocumentSymbol sym = new DocumentSymbol();
        sym.setName(name);
        sym.setKind(kind);
        sym.setRange(new Range(new Position(startLine, startChar), new Position(endLine, endChar)));
        sym.setSelectionRange(new Range(new Position(startLine, startChar), new Position(startLine, startChar + name.length())));
        if (children.length > 0) {
            sym.setChildren(List.of(children));
        }
        return sym;
    }
}
