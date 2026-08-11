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

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

/**
 * Finds the deepest enclosing symbol for a given position in a document symbol tree.
 */
public final class EnclosingSymbolFinder {

    private EnclosingSymbolFinder() {
    }

    /**
     * Find the deepest DocumentSymbol whose range contains the given position.
     *
     * @param symbols document symbols (hierarchical or flat)
     * @param position the position to find the enclosing symbol for
     * @return the deepest enclosing symbol, or null if none found
     */
    static DocumentSymbol findEnclosing(
            List<Either<SymbolInformation, DocumentSymbol>> symbols, Position position) {
        if (symbols == null || symbols.isEmpty()) {
            return null;
        }
        DocumentSymbol best = null;
        for (Either<SymbolInformation, DocumentSymbol> either : symbols) {
            if (!either.isRight()) continue;
            DocumentSymbol candidate = findDeepest(either.getRight(), position);
            if (candidate != null) {
                if (best == null || isNarrower(candidate.getRange(), best.getRange())) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static DocumentSymbol findDeepest(DocumentSymbol symbol, Position position) {
        if (!containsPosition(symbol.getRange(), position)) {
            return null;
        }
        if (symbol.getChildren() != null) {
            for (DocumentSymbol child : symbol.getChildren()) {
                DocumentSymbol deeper = findDeepest(child, position);
                if (deeper != null) {
                    return deeper;
                }
            }
        }
        return symbol;
    }

    static boolean containsPosition(Range range, Position position) {
        int line = position.getLine();
        int startLine = range.getStart().getLine();
        int endLine = range.getEnd().getLine();

        if (line < startLine || line > endLine) return false;
        if (line == startLine && position.getCharacter() < range.getStart().getCharacter()) return false;
        if (line == endLine && position.getCharacter() > range.getEnd().getCharacter()) return false;
        return true;
    }

    private static boolean isNarrower(Range a, Range b) {
        int aLines = a.getEnd().getLine() - a.getStart().getLine();
        int bLines = b.getEnd().getLine() - b.getStart().getLine();
        return aLines < bLines;
    }
}
