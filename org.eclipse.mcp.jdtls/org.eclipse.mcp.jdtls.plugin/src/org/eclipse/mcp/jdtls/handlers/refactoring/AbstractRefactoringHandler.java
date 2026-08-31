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
package org.eclipse.mcp.jdtls.handlers.refactoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;
import org.eclipse.text.edits.InsertEdit;

/**
 * Base class for refactoring handlers providing common utility methods.
 *
 * <p>Provides shared logic for parsing arguments, resolving elements at positions,
 * and converting JDT TextEdits to the serializable format expected by MCP clients.</p>
 */
public abstract class AbstractRefactoringHandler implements ICommandHandler {

    /**
     * Parse the first argument as a Map of parameters.
     *
     * @param arguments the command arguments list
     * @return the parameters map, or an empty map if arguments are null/empty
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> parseParams(List<Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Object first = arguments.get(0);
        if (first instanceof Map) {
            return (Map<String, Object>) first;
        }
        return Map.of();
    }

    /**
     * Resolve the IJavaElement at the position specified by {uri, line, character} in the arguments.
     *
     * @param arguments the command arguments containing {uri, line, character}
     * @param monitor   the progress monitor
     * @return the resolved elements array, or null if resolution fails
     * @throws JavaModelException if an error occurs during code select
     */
    protected IJavaElement[] resolveElementAtPosition(List<Object> arguments, IProgressMonitor monitor)
            throws JavaModelException {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");
        if (uri == null) {
            return null;
        }
        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        ICompilationUnit cu = JdtUtils.getCompilationUnit(uri);
        if (cu == null) {
            return null;
        }

        int offset = JdtUtils.getOffset(cu, line, character);
        return cu.codeSelect(offset, 0);
    }

    /**
     * Create a single whole-file replacement edit.
     *
     * @param uri       the file URI
     * @param source    the original source
     * @param newSource the new source
     * @return a list containing one edit that replaces the entire file content
     */
    protected List<Map<String, Object>> createWholeFileEdit(String uri, String source, String newSource) {
        List<Map<String, Object>> edits = new ArrayList<>();
        int lineCount = countLines(source);

        Map<String, Object> edit = new HashMap<>();
        edit.put("uri", uri);
        edit.put("range", createRange(0, 0, lineCount, 0));
        edit.put("newText", newSource);
        edits.add(edit);
        return edits;
    }

    /**
     * Compute line-level edits between original and new source.
     *
     * @param uri       the file URI
     * @param source    the original source
     * @param newSource the new source
     * @return a list of edit maps
     */
    protected List<Map<String, Object>> computeLineEdits(String uri, String source, String newSource) {
        // For simplicity, produce a single whole-file replacement edit
        return createWholeFileEdit(uri, source, newSource);
    }

    /**
     * Create a targeted insert edit at a specific offset in the source.
     * Unlike {@link #createWholeFileEdit}, this only sends the inserted text,
     * not the entire file content — saving significant tokens.
     *
     * @param uri          the file URI
     * @param source       the original source (used to compute line/character from offset)
     * @param insertOffset the absolute offset in the source where the text should be inserted
     * @param insertText   the text to insert
     * @return a list containing one edit entry with a single textEdit
     */
    protected List<Map<String, Object>> createInsertEdit(String uri, String source,
            int insertOffset, String insertText) {
        int line = 0;
        int character = 0;
        for (int i = 0; i < Math.min(insertOffset, source.length()); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }

        Map<String, Object> textEdit = new HashMap<>();
        textEdit.put("range", createRange(line, character, line, character));
        textEdit.put("newText", insertText);

        Map<String, Object> edit = new HashMap<>();
        edit.put("uri", uri);
        edit.put("textEdits", List.of(textEdit));

        return List.of(edit);
    }

    /**
     * Create a result map indicating an error.
     *
     * @param message the error message
     * @return a map with "applied" set to false and an "error" message
     */
    protected Map<String, Object> createErrorResult(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("applied", false);
        result.put("error", message);
        result.put("edits", List.of());
        return result;
    }

    /**
     * Create a successful result map with the given edits (preview mode).
     *
     * @param edits the list of edit maps
     * @return a map with "applied" set to false and the edits as preview
     */
    protected Map<String, Object> createSuccessResult(List<Map<String, Object>> edits) {
        return createSuccessResult(edits, false);
    }

    /**
     * Create a successful result map with the given edits.
     *
     * @param edits   the list of edit maps
     * @param applied whether the changes were applied to disk
     * @return a map with "applied" status and the edits
     */
    protected Map<String, Object> createSuccessResult(List<Map<String, Object>> edits, boolean applied) {
        Map<String, Object> result = new HashMap<>();
        result.put("applied", applied && !edits.isEmpty());
        result.put("edits", edits);
        return result;
    }

    /**
     * Read the "apply" flag from the parameters map.
     *
     * @param params the command parameters
     * @return true if the refactoring should be applied to disk, false for preview only
     */
    protected static boolean isApply(Map<String, Object> params) {
        Object apply = params.get("apply");
        return Boolean.TRUE.equals(apply);
    }

    /**
     * Apply an insert edit and return a success result.
     * If apply is true, the text is inserted into the compilation unit and saved to disk.
     */
    protected Map<String, Object> applyInsertEdit(String uri, ICompilationUnit cu, String source,
            int insertOffset, String insertText, boolean apply, IProgressMonitor monitor) throws Exception {
        List<Map<String, Object>> edits = createInsertEdit(uri, source, insertOffset, insertText);
        if (apply) {
            cu.applyTextEdit(new InsertEdit(insertOffset, insertText), monitor);
            cu.save(monitor, true);
        }
        return createSuccessResult(edits, apply);
    }

    /**
     * Create an LSP-compatible range map.
     *
     * @param startLine      start line (0-based)
     * @param startCharacter start character (0-based)
     * @param endLine        end line (0-based)
     * @param endCharacter   end character (0-based)
     * @return a range map
     */
    protected Map<String, Object> createRange(int startLine, int startCharacter, int endLine, int endCharacter) {
        Map<String, Object> start = new HashMap<>();
        start.put("line", startLine);
        start.put("character", startCharacter);

        Map<String, Object> end = new HashMap<>();
        end.put("line", endLine);
        end.put("character", endCharacter);

        Map<String, Object> range = new HashMap<>();
        range.put("start", start);
        range.put("end", end);
        return range;
    }

    /**
     * Parse an AST from a compilation unit with bindings resolved.
     *
     * @param cu      the compilation unit
     * @param monitor the progress monitor
     * @return the parsed CompilationUnit AST node
     */
    protected CompilationUnit parseAST(ICompilationUnit cu, IProgressMonitor monitor) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        return (CompilationUnit) parser.createAST(monitor);
    }

    /**
     * Count the number of lines in a string.
     *
     * @param text the text to count lines in
     * @return the number of lines
     */
    protected int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * Compute the offset in source for a given line and character (0-based).
     *
     * @param source    the source text
     * @param line      the line number (0-based)
     * @param character the character offset in the line (0-based)
     * @return the absolute offset
     */
    protected int getOffset(String source, int line, int character) {
        int currentLine = 0;
        for (int i = 0; i < source.length(); i++) {
            if (currentLine == line) {
                return i + character;
            }
            if (source.charAt(i) == '\n') {
                currentLine++;
            }
        }
        return source.length();
    }
}
