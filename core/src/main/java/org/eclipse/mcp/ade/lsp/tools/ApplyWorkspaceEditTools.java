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

import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import org.eclipse.mcp.ade.tools.ToolException;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP tool for applying text edits to files on disk.
 */
@ApplicationScoped
public class ApplyWorkspaceEditTools {

    @Tool(name = "apply_workspace_edit",
          description = "Apply text edits to a file on disk. " +
                        "Each edit replaces a range (startLine:startCharacter to endLine:endCharacter) with new text. " +
                        "Use this after tools that return edit previews (formatting, code actions, etc.) to apply the changes. " +
                        "Example: apply_workspace_edit(fileUri='file:///home/user/project/src/main.py', " +
                        "startLines=[5,10], startCharacters=[0,0], endLines=[5,12], endCharacters=[10,0], newTexts=['replacement',''])")
    public String applyWorkspaceEdit(
            @ToolArg(description = ToolArgDescriptions.URI) String fileUri,
            @ToolArg(description = "Start line numbers of each edit (0-based)") List<Integer> startLines,
            @ToolArg(description = "Start character positions of each edit (0-based)") List<Integer> startCharacters,
            @ToolArg(description = "End line numbers of each edit (0-based)") List<Integer> endLines,
            @ToolArg(description = "End character positions of each edit (0-based)") List<Integer> endCharacters,
            @ToolArg(description = "New text for each edit (empty string to delete)") List<String> newTexts) {

        if (fileUri == null || fileUri.isEmpty()) {
            throw new ToolException("fileUri must be provided");
        }

        int size = startLines.size();
        if (startCharacters.size() != size || endLines.size() != size ||
                endCharacters.size() != size || newTexts.size() != size) {
            throw new ToolException("All edit arrays must have the same length");
        }

        if (size == 0) {
            return "No edits to apply";
        }

        List<TextEdit> edits = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            TextEdit edit = new TextEdit();
            edit.setRange(new Range(
                    new Position(startLines.get(i), startCharacters.get(i)),
                    new Position(endLines.get(i), endCharacters.get(i))));
            edit.setNewText(newTexts.get(i));
            edits.add(edit);
        }

        int count = TextEditApplier.applyTextEdits(fileUri, edits);

        return String.format("Applied %d edit(s) to %s", count, fileUri);
    }
}
