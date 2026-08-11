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
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a symbol name path (e.g., "MyClass/myMethod") against an LSP DocumentSymbol tree.
 * <p>
 * Name path format:
 * <ul>
 *   <li>{@code "symbol"} - matches any symbol with that name</li>
 *   <li>{@code "parent/child"} - navigates the hierarchy</li>
 *   <li>{@code "parent/child[0]"} - disambiguates overloads by index</li>
 * </ul>
 */
public final class SymbolNamePathResolver {

    private static final Pattern INDEX_PATTERN = Pattern.compile("^(.+)\\[(\\d+)]$");

    private SymbolNamePathResolver() {
    }

    /**
     * Find a unique DocumentSymbol matching the given name path.
     *
     * @param symbols  the document symbol tree from LSP
     * @param namePath the name path (e.g., "MyClass/myMethod", "book[1]", "catalog/book")
     * @return the matching DocumentSymbol
     * @throws ToolException if no match or ambiguous match
     */
    public static DocumentSymbol resolve(List<Either<SymbolInformation, DocumentSymbol>> symbols, String namePath) {
        List<DocumentSymbol> docSymbols = toDocumentSymbols(symbols != null ? symbols : List.of());
        if (docSymbols.isEmpty()) {
            throw new ToolException(
                    "No document symbols found. The language server may not support hierarchical document symbols.");
        }

        String[] segments = namePath.split("/");
        List<DocumentSymbol> candidates = findByPath(docSymbols, segments, 0);

        if (candidates.isEmpty()) {
            // Fallback: search recursively by the last segment name
            candidates = findByNameRecursive(docSymbols, segments[segments.length - 1]);
        }

        if (candidates.isEmpty()) {
            throw new ToolException("Symbol not found: '" + namePath + "'");
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder("Ambiguous symbol path '")
                    .append(namePath).append("', found ").append(candidates.size()).append(" matches:");
            for (int i = 0; i < candidates.size(); i++) {
                DocumentSymbol s = candidates.get(i);
                sb.append("\n  [").append(i).append("] ").append(s.getName())
                        .append(" (").append(s.getKind()).append(") at line ")
                        .append(s.getRange().getStart().getLine() + 1);
            }
            sb.append("\nUse an index suffix to disambiguate, e.g., '").append(namePath).append("[0]'");
            throw new ToolException(sb.toString());
        }
        return candidates.get(0);
    }

    private static List<DocumentSymbol> toDocumentSymbols(List<Either<SymbolInformation, DocumentSymbol>> symbols) {
        List<DocumentSymbol> result = new ArrayList<>();
        for (var either : symbols) {
            if (either.isRight()) {
                result.add(either.getRight());
            }
        }
        return result;
    }

    private static List<DocumentSymbol> findByPath(List<DocumentSymbol> symbols, String[] segments, int depth) {
        if (depth >= segments.length) {
            return List.of();
        }

        String segment = segments[depth];
        boolean isLast = depth == segments.length - 1;

        int index = -1;
        Matcher m = INDEX_PATTERN.matcher(segment);
        if (m.matches()) {
            segment = m.group(1);
            index = Integer.parseInt(m.group(2));
        }

        List<DocumentSymbol> matches = new ArrayList<>();
        for (DocumentSymbol sym : symbols) {
            if (sym.getName().equals(segment)) {
                matches.add(sym);
            }
        }

        if (index >= 0) {
            if (index < matches.size()) {
                matches = List.of(matches.get(index));
            } else {
                return List.of();
            }
        }

        if (isLast) {
            return matches;
        }

        List<DocumentSymbol> result = new ArrayList<>();
        for (DocumentSymbol match : matches) {
            if (match.getChildren() != null) {
                result.addAll(findByPath(match.getChildren(), segments, depth + 1));
            }
        }
        return result;
    }

    private static List<DocumentSymbol> findByNameRecursive(List<DocumentSymbol> symbols, String name) {
        int index = -1;
        String baseName = name;
        Matcher m = INDEX_PATTERN.matcher(name);
        if (m.matches()) {
            baseName = m.group(1);
            index = Integer.parseInt(m.group(2));
        }

        List<DocumentSymbol> found = new ArrayList<>();
        collectByName(symbols, baseName, found);

        if (index >= 0) {
            if (index < found.size()) {
                return List.of(found.get(index));
            }
            return List.of();
        }
        return found;
    }

    private static void collectByName(List<DocumentSymbol> symbols, String name, List<DocumentSymbol> result) {
        for (DocumentSymbol sym : symbols) {
            if (sym.getName().equals(name)) {
                result.add(sym);
            }
            if (sym.getChildren() != null) {
                collectByName(sym.getChildren(), name, result);
            }
        }
    }
}
