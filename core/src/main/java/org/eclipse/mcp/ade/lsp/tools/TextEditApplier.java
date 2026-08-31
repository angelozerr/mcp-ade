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

import org.eclipse.mcp.ade.tools.ToolException;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Applies LSP TextEdits and WorkspaceEdits to files on disk.
 */
public final class TextEditApplier {

    private TextEditApplier() {
    }

    /**
     * Apply a list of TextEdits to a file on disk.
     *
     * @param fileUri the file URI
     * @param edits   the text edits to apply
     * @return the number of edits applied
     */
    public static int applyTextEdits(String fileUri, List<? extends TextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return 0;
        }

        Path filePath = Path.of(URI.create(fileUri));
        String content;
        try {
            content = Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Failed to read file: " + fileUri, e);
        }

        String result = applyEditsToContent(content, edits);

        try {
            Files.writeString(filePath, result, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolException("Failed to write file: " + fileUri, e);
        }

        return edits.size();
    }

    /**
     * Apply a WorkspaceEdit to files on disk.
     *
     * @param edit the workspace edit
     * @return summary of applied changes (file URI -> number of edits)
     */
    public static Map<String, Integer> applyWorkspaceEdit(WorkspaceEdit edit) {
        Map<String, Integer> summary = new LinkedHashMap<>();

        if (edit.getDocumentChanges() != null) {
            for (Either<TextDocumentEdit, ResourceOperation> change : edit.getDocumentChanges()) {
                if (change.isLeft()) {
                    TextDocumentEdit docEdit = change.getLeft();
                    String uri = docEdit.getTextDocument().getUri();
                    List<TextEdit> textEdits = new ArrayList<>();
                    for (Either<TextEdit, SnippetTextEdit> te : docEdit.getEdits()) {
                        if (te.isLeft()) {
                            textEdits.add(te.getLeft());
                        }
                    }
                    int count = applyTextEdits(uri, textEdits);
                    summary.put(uri, count);
                }
            }
        } else if (edit.getChanges() != null) {
            for (Map.Entry<String, List<TextEdit>> entry : edit.getChanges().entrySet()) {
                int count = applyTextEdits(entry.getKey(), entry.getValue());
                summary.put(entry.getKey(), count);
            }
        }

        return summary;
    }

    /**
     * Apply text edits to a string content and return the modified content.
     * Edits are applied in reverse order (bottom-to-top) to preserve positions.
     */
    static String applyEditsToContent(String content, List<? extends TextEdit> edits) {
        int[] lineOffsets = computeLineOffsets(content);

        List<? extends TextEdit> sortedEdits = new ArrayList<>(edits);
        sortedEdits.sort((a, b) -> {
            int lineCmp = Integer.compare(b.getRange().getStart().getLine(), a.getRange().getStart().getLine());
            if (lineCmp != 0) return lineCmp;
            return Integer.compare(b.getRange().getStart().getCharacter(), a.getRange().getStart().getCharacter());
        });

        StringBuilder sb = new StringBuilder(content);
        for (TextEdit edit : sortedEdits) {
            int startOffset = positionToOffset(lineOffsets, edit.getRange().getStart(), content.length());
            int endOffset = positionToOffset(lineOffsets, edit.getRange().getEnd(), content.length());
            sb.replace(startOffset, endOffset, edit.getNewText() != null ? edit.getNewText() : "");
        }

        return sb.toString();
    }

    private static int[] computeLineOffsets(String content) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\n') {
                offsets.add(i + 1);
            } else if (c == '\r') {
                if (i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                offsets.add(i + 1);
            }
        }
        return offsets.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int positionToOffset(int[] lineOffsets, Position position, int contentLength) {
        int line = position.getLine();
        if (line < 0) return 0;
        if (line >= lineOffsets.length) return contentLength;
        int offset = lineOffsets[line] + position.getCharacter();
        return Math.min(offset, contentLength);
    }
}
