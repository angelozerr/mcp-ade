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
package org.eclipse.mcp.ade.lsp.tools.strategies;

import org.eclipse.lsp4j.*;
import org.eclipse.mcp.ade.lsp.tools.params.RenameRequestParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RenameStrategyTest {

    @TempDir
    Path tempDir;

    private final RenameStrategy strategy = new RenameStrategy(null);

    @Test
    void formatResultsWithApplyTrue() throws IOException {
        Path file = tempDir.resolve("MyClass.java");
        Files.writeString(file, "class OldName {\n    OldName instance;\n}\n", StandardCharsets.UTF_8);

        String fileUri = file.toUri().toString();
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setChanges(Map.of(fileUri, List.of(
                textEdit(0, 6, 0, 13, "NewName"),
                textEdit(1, 4, 1, 11, "NewName")
        )));

        RenameRequestParams params = new RenameRequestParams(tempDir.toString(), fileUri, 0, 6, "NewName", true);
        String result = strategy.formatResults(params, List.of(edit));

        assertEquals("Renamed 'NewName' in 1 file(s) (2 edit(s) applied).", result);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("class NewName {\n    NewName instance;\n}\n", content);
    }

    @Test
    void formatResultsWithApplyFalse() throws IOException {
        Path file = tempDir.resolve("MyClass.java");
        Files.writeString(file, "class OldName {}\n", StandardCharsets.UTF_8);

        String fileUri = file.toUri().toString();
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setChanges(Map.of(fileUri, List.of(
                textEdit(0, 6, 0, 13, "NewName")
        )));

        RenameRequestParams params = new RenameRequestParams(tempDir.toString(), fileUri, 0, 6, "NewName", false);
        String result = strategy.formatResults(params, List.of(edit));

        assertTrue(result.contains("NewName"), "Preview should contain the new name");
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("class OldName {}\n", content, "File should not be modified when apply=false");
    }

    @Test
    void formatResultsWithApplyMultipleFiles() throws IOException {
        Path file1 = tempDir.resolve("A.java");
        Path file2 = tempDir.resolve("B.java");
        Files.writeString(file1, "class A { Foo foo; }\n", StandardCharsets.UTF_8);
        Files.writeString(file2, "class B { Foo bar; }\n", StandardCharsets.UTF_8);

        String uri1 = file1.toUri().toString();
        String uri2 = file2.toUri().toString();
        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setChanges(Map.of(
                uri1, List.of(textEdit(0, 10, 0, 13, "Bar")),
                uri2, List.of(textEdit(0, 10, 0, 13, "Bar"))
        ));

        RenameRequestParams params = new RenameRequestParams(tempDir.toString(), uri1, 0, 10, "Bar", true);
        String result = strategy.formatResults(params, List.of(edit));

        assertEquals("Renamed 'Bar' in 2 file(s) (2 edit(s) applied).", result);
        assertTrue(Files.readString(file1, StandardCharsets.UTF_8).contains("Bar"));
        assertTrue(Files.readString(file2, StandardCharsets.UTF_8).contains("Bar"));
    }

    @Test
    void formatResultsWithEmptyEdits() {
        RenameRequestParams params = new RenameRequestParams(tempDir.toString(), "file:///test.java", 0, 0, "NewName", true);
        String result = strategy.formatResults(params, List.of());

        assertEquals("Renamed 'NewName' in 0 file(s) (0 edit(s) applied).", result);
    }

    @Test
    void formatResultsWithApplyAndFileRename() throws IOException {
        Path file = tempDir.resolve("OldName.java");
        Files.writeString(file, "class OldName {}\n", StandardCharsets.UTF_8);

        String oldUri = file.toUri().toString();
        Path newFile = tempDir.resolve("NewName.java");
        String newUri = newFile.toUri().toString();

        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setDocumentChanges(List.of(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
                        new TextDocumentEdit(
                                new VersionedTextDocumentIdentifier(oldUri, 1),
                                List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
                                        textEdit(0, 6, 0, 13, "NewName"))))),
                org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                        new RenameFile(oldUri, newUri))
        ));

        RenameRequestParams params = new RenameRequestParams(tempDir.toString(), oldUri, 0, 6, "NewName", true);
        String result = strategy.formatResults(params, List.of(edit));

        assertTrue(result.contains("2 file(s)"));
        assertFalse(Files.exists(file), "Old file should no longer exist");
        assertTrue(Files.exists(newFile), "New file should exist");
        assertEquals("class NewName {}\n", Files.readString(newFile, StandardCharsets.UTF_8));
    }

    @Test
    void formatResultsWithApplyAndCreateFile() throws IOException {
        Path newFile = tempDir.resolve("Generated.java");
        String newUri = newFile.toUri().toString();

        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setDocumentChanges(List.of(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                        new CreateFile(newUri))
        ));

        RenameRequestParams params = new RenameRequestParams(tempDir.toString(), newUri, 0, 0, "Generated", true);
        String result = strategy.formatResults(params, List.of(edit));

        assertTrue(Files.exists(newFile), "File should be created");
    }

    @Test
    void formatResultsWithApplyAndDeleteFile() throws IOException {
        Path file = tempDir.resolve("ToDelete.java");
        Files.writeString(file, "class ToDelete {}\n", StandardCharsets.UTF_8);
        String uri = file.toUri().toString();

        WorkspaceEdit edit = new WorkspaceEdit();
        edit.setDocumentChanges(List.of(
                org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                        new DeleteFile(uri))
        ));

        RenameRequestParams params = new RenameRequestParams(tempDir.toString(), uri, 0, 0, "X", true);
        String result = strategy.formatResults(params, List.of(edit));

        assertFalse(Files.exists(file), "File should be deleted");
    }

    private static TextEdit textEdit(int startLine, int startChar, int endLine, int endChar, String newText) {
        return new TextEdit(
                new Range(new Position(startLine, startChar), new Position(endLine, endChar)),
                newText);
    }
}
