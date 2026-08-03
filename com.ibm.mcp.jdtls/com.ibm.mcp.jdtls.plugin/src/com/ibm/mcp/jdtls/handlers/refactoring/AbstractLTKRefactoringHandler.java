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
package com.ibm.mcp.jdtls.handlers.refactoring;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Base class for refactoring handlers that delegate to the JDT LTK refactoring engine.
 *
 * <p>Subclasses configure and create a {@link Refactoring}, then call
 * {@link #executeRefactoring(Refactoring, IProgressMonitor)} which checks conditions,
 * creates the change, and converts it to the MCP edit format.</p>
 */
public abstract class AbstractLTKRefactoringHandler extends AbstractRefactoringHandler {

    /**
     * Execute an LTK refactoring in preview mode (changes are NOT applied to disk).
     */
    protected Map<String, Object> executeRefactoring(Refactoring refactoring, IProgressMonitor monitor)
            throws CoreException {
        return executeRefactoring(refactoring, false, monitor);
    }

    /**
     * Execute an LTK refactoring, reading the "apply" flag from the params map.
     */
    protected Map<String, Object> executeRefactoring(Refactoring refactoring, Map<String, Object> params,
            IProgressMonitor monitor) throws CoreException {
        return executeRefactoring(refactoring, isApply(params), monitor);
    }

    /**
     * Execute an LTK refactoring and return the result in MCP edit format.
     *
     * @param refactoring the LTK refactoring to execute
     * @param apply       if true, apply changes to disk; if false, return preview only
     * @param monitor     the progress monitor
     * @return the result map with edits and applied status
     */
    protected Map<String, Object> executeRefactoring(Refactoring refactoring, boolean apply,
            IProgressMonitor monitor) throws CoreException {
        RefactoringStatus initialStatus = refactoring.checkInitialConditions(monitor);
        if (initialStatus.hasFatalError()) {
            return createErrorResult("Refactoring precondition failed: " + initialStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
        }

        RefactoringStatus finalStatus;
        try {
            finalStatus = refactoring.checkFinalConditions(monitor);
        } catch (RuntimeException e) {
            return createErrorResult("Refactoring failed: " + e.getMessage());
        }
        if (finalStatus.hasFatalError()) {
            return createErrorResult("Refactoring validation failed: " + finalStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
        }

        Change change;
        try {
            change = refactoring.createChange(monitor);
        } catch (RuntimeException e) {
            return createErrorResult("Refactoring failed: " + e.getMessage());
        }
        if (change == null) {
            return createErrorResult("Refactoring produced no changes");
        }

        List<Map<String, Object>> edits = convertChangeToEdits(change);

        if (edits.isEmpty()) {
            edits = convertChangeToWholeFileEdits(change);
        }

        if (edits.isEmpty()) {
            return createErrorResult("Refactoring produced no text edits");
        }

        if (apply) {
            change.perform(monitor);
        }

        return createSuccessResult(edits, apply);
    }

    /**
     * Convert an LTK {@link Change} tree into a list of per-file edit maps
     * using LSP-style range (line/character) format.
     *
     * <p>Output format per entry:
     * <pre>{uri: "file:...", textEdits: [{range: {start: {line, character}, end: {line, character}}, newText: "..."}]}</pre>
     */
    protected List<Map<String, Object>> convertChangeToEdits(Change change) {
        Map<String, FileEdits> editsByUri = new java.util.LinkedHashMap<>();
        collectEdits(change, editsByUri);

        List<Map<String, Object>> result = new ArrayList<>();
        for (FileEdits fileEdits : editsByUri.values()) {
            if (fileEdits.textEdits.isEmpty()) {
                continue;
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("uri", fileEdits.uri);
            entry.put("textEdits", fileEdits.textEdits);
            result.add(entry);
        }
        return result;
    }

    private void collectEdits(Change change, Map<String, FileEdits> editsByUri) {
        if (change instanceof CompositeChange) {
            for (Change child : ((CompositeChange) change).getChildren()) {
                collectEdits(child, editsByUri);
            }
        } else if (change instanceof TextChange) {
            TextChange textChange = (TextChange) change;
            TextEdit edit = textChange.getEdit();
            if (edit == null) {
                return;
            }

            String uri = getChangeUri(change);
            if (uri == null) {
                return;
            }

            String source = null;
            try {
                source = textChange.getCurrentContent(new NullProgressMonitor());
            } catch (CoreException e) {
                // fall through with null source
            }

            FileEdits fileEdits = editsByUri.computeIfAbsent(uri, k -> new FileEdits(k));
            collectTextEdits(edit, source, fileEdits.textEdits);
        }
    }

    private void collectTextEdits(TextEdit edit, String source, List<Map<String, Object>> textEdits) {
        if (edit instanceof MultiTextEdit) {
            for (TextEdit child : edit.getChildren()) {
                collectTextEdits(child, source, textEdits);
            }
        } else if (edit instanceof ReplaceEdit) {
            ReplaceEdit replace = (ReplaceEdit) edit;
            textEdits.add(createTextEdit(source, replace.getOffset(), replace.getLength(), replace.getText()));
        } else if (edit instanceof InsertEdit) {
            InsertEdit insert = (InsertEdit) edit;
            textEdits.add(createTextEdit(source, insert.getOffset(), 0, insert.getText()));
        } else if (edit instanceof DeleteEdit) {
            DeleteEdit delete = (DeleteEdit) edit;
            textEdits.add(createTextEdit(source, delete.getOffset(), delete.getLength(), ""));
        }
    }

    private Map<String, Object> createTextEdit(String source, int offset, int length, String newText) {
        Map<String, Object> edit = new HashMap<>();
        edit.put("range", createRange(source, offset, length));
        edit.put("newText", newText);
        return edit;
    }

    private Map<String, Object> createRange(String source, int offset, int length) {
        Map<String, Object> range = new HashMap<>();
        range.put("start", offsetToPosition(source, offset));
        range.put("end", offsetToPosition(source, offset + length));
        return range;
    }

    private Map<String, Object> offsetToPosition(String source, int offset) {
        int line = 0;
        int character = 0;
        if (source != null) {
            for (int i = 0; i < Math.min(offset, source.length()); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                    character = 0;
                } else {
                    character++;
                }
            }
        }
        return Map.of("line", line, "character", character);
    }

    private static class FileEdits {
        final String uri;
        final List<Map<String, Object>> textEdits = new ArrayList<>();

        FileEdits(String uri) {
            this.uri = uri;
        }
    }

    private String getChangeUri(Change change) {
        if (change instanceof TextFileChange) {
            TextFileChange fileChange = (TextFileChange) change;
            if (fileChange.getFile() != null) {
                return JdtUtils.toFileUri(fileChange.getFile());
            }
        }
        Object modifiedElement = change.getModifiedElement();
        if (modifiedElement instanceof ICompilationUnit) {
            ICompilationUnit cu = (ICompilationUnit) modifiedElement;
            if (cu.getResource() != null) {
                return JdtUtils.toFileUri(cu.getResource());
            }
        }
        return null;
    }

    /**
     * Convert an LTK Change to a whole-file replacement edit.
     * This is a simpler approach that applies the change to get the new content.
     */
    protected List<Map<String, Object>> convertChangeToWholeFileEdits(Change change) throws CoreException {
        List<Map<String, Object>> edits = new ArrayList<>();
        collectWholeFileEdits(change, edits);
        return edits;
    }

    private void collectWholeFileEdits(Change change, List<Map<String, Object>> edits) throws CoreException {
        if (change instanceof CompositeChange) {
            for (Change child : ((CompositeChange) change).getChildren()) {
                collectWholeFileEdits(child, edits);
            }
        } else if (change instanceof TextChange) {
            TextChange textChange = (TextChange) change;
            String uri = getChangeUri(change);
            if (uri == null) {
                return;
            }

            String preview = textChange.getPreviewContent(new NullProgressMonitor());
            String current = textChange.getCurrentContent(new NullProgressMonitor());

            if (preview != null && current != null && !preview.equals(current)) {
                edits.addAll(createWholeFileEdit(uri, current, preview));
            }
        }
    }
}
