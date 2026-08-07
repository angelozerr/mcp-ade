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
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.util.CodeFormatterUtil;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextEditBasedChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.CreateCompilationUnitChange;
import org.eclipse.text.edits.DeleteEdit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;

import com.ibm.mcp.jdtls.JdtUtils;

import org.eclipse.jdt.core.JavaModelException;

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
            throw new RuntimeException("Refactoring precondition failed: "
                    + initialStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
        }
        if (initialStatus.hasError()) {
            throw new RuntimeException("Refactoring precondition failed: "
                    + initialStatus.getMessageMatchingSeverity(RefactoringStatus.ERROR));
        }

        RefactoringStatus finalStatus;
        try {
            finalStatus = refactoring.checkFinalConditions(monitor);
        } catch (RuntimeException e) {
            throw new RuntimeException("Refactoring validation failed: " + getRuntimeExceptionMessage(e));
        }
        if (finalStatus.hasFatalError()) {
            throw new RuntimeException("Refactoring validation failed: "
                    + finalStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL));
        }
        if (isFinalConditionErrorBlocking() && finalStatus.hasError()) {
            throw new RuntimeException("Refactoring validation failed: "
                    + finalStatus.getMessageMatchingSeverity(RefactoringStatus.ERROR));
        }

        Change change;
        try {
            change = refactoring.createChange(monitor);
        } catch (RuntimeException e) {
            throw new RuntimeException("Refactoring failed: " + getRuntimeExceptionMessage(e));
        }
        if (change == null) {
            throw new RuntimeException("Refactoring produced no changes");
        }

        try {
            List<Map<String, Object>> edits = convertChangeToEdits(change);

            if (edits.isEmpty()) {
                throw new RuntimeException("Refactoring produced no text edits");
            }

            if (apply) {
                change.perform(monitor);
            }

            return createSuccessResult(edits, apply);
        } finally {
            reconcileAffectedCompilationUnits(change);
        }
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
        } else if (change instanceof TextChange textChange) {
            collectTextChangeEdits(textChange, editsByUri);
        } else if (change instanceof CreateCompilationUnitChange createCUChange) {
            collectCreateCUEdits(createCUChange, editsByUri);
        } else if (change instanceof TextEditBasedChange tebChange) {
            collectTextEditBasedChangeEdits(tebChange, editsByUri);
        }
    }

    private void collectTextChangeEdits(TextChange textChange, Map<String, FileEdits> editsByUri) {
        TextEdit edit = textChange.getEdit();
        if (edit == null) {
            return;
        }

        String uri = getChangeUri(textChange);
        if (uri == null) {
            return;
        }

        FileEdits fileEdits = editsByUri.computeIfAbsent(uri, k -> new FileEdits(k));

        if (hasUnsupportedEdits(edit)) {
            try {
                String current = textChange.getCurrentContent(new NullProgressMonitor());
                String preview = textChange.getPreviewContent(new NullProgressMonitor());
                if (preview != null && current != null && !preview.equals(current)) {
                    fileEdits.textEdits.add(createTextEdit(current, 0, current.length(), preview));
                }
            } catch (CoreException e) {
                // fall through
            }
        } else {
            String source = null;
            try {
                source = textChange.getCurrentContent(new NullProgressMonitor());
            } catch (CoreException e) {
                // fall through with null source
            }
            collectTextEdits(edit, source, fileEdits.textEdits);
        }
    }

    private void collectCreateCUEdits(CreateCompilationUnitChange createCUChange,
            Map<String, FileEdits> editsByUri) {
        ICompilationUnit cu = createCUChange.getCu();
        if (cu != null && cu.getResource() != null) {
            String uri = JdtUtils.toFileUri(cu.getResource());
            String preview = createCUChange.getPreview();
            if (uri != null && preview != null && !preview.isEmpty()) {
                FileEdits fileEdits = editsByUri.computeIfAbsent(uri, k -> new FileEdits(k));
                fileEdits.textEdits.add(createTextEdit("", 0, 0, preview));
            }
        }
    }

    private void collectTextEditBasedChangeEdits(TextEditBasedChange tebChange,
            Map<String, FileEdits> editsByUri) {
        String uri = getChangeUri(tebChange);
        if (uri == null) {
            return;
        }
        try {
            String current = tebChange.getCurrentContent(new NullProgressMonitor());
            String preview = tebChange.getPreviewContent(new NullProgressMonitor());
            if (preview != null && current != null && !preview.equals(current)) {
                FileEdits fileEdits = editsByUri.computeIfAbsent(uri, k -> new FileEdits(k));
                fileEdits.textEdits.add(createTextEdit(current, 0, current.length(), preview));
            }
        } catch (CoreException e) {
            // skip this change
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
        if (modifiedElement instanceof ICompilationUnit cu) {
            if (cu.getResource() != null) {
                return JdtUtils.toFileUri(cu.getResource());
            }
        } else if (modifiedElement instanceof org.eclipse.core.resources.IFile file) {
            return JdtUtils.toFileUri(file);
        }
        return null;
    }

    private boolean hasUnsupportedEdits(TextEdit edit) {
        if (edit instanceof org.eclipse.text.edits.MoveSourceEdit
                || edit instanceof org.eclipse.text.edits.MoveTargetEdit
                || edit instanceof org.eclipse.text.edits.CopySourceEdit
                || edit instanceof org.eclipse.text.edits.CopyTargetEdit) {
            return true;
        }
        for (TextEdit child : edit.getChildren()) {
            if (hasUnsupportedEdits(child)) {
                return true;
            }
        }
        return false;
    }

    private void reconcileAffectedCompilationUnits(Change change) {
        java.util.Set<ICompilationUnit> cus = new java.util.LinkedHashSet<>();
        collectAffectedCUs(change, cus);
        for (ICompilationUnit cu : cus) {
            try {
                if (cu.isWorkingCopy()) {
                    cu.reconcile(ICompilationUnit.NO_AST, true, null, null);
                } else {
                    cu.close();
                }
            } catch (JavaModelException e) {
                // best effort
            }
        }
    }

    private void collectAffectedCUs(Change change, java.util.Set<ICompilationUnit> cus) {
        if (change instanceof CompositeChange) {
            for (Change child : ((CompositeChange) change).getChildren()) {
                collectAffectedCUs(child, cus);
            }
        }
        if (change instanceof TextFileChange tfc) {
            if (tfc.getFile() != null) {
                ICompilationUnit cu = JdtUtils.getCompilationUnit(JdtUtils.toFileUri(tfc.getFile()));
                if (cu != null) {
                    cus.add(cu);
                }
            }
        } else if (change instanceof TextChange) {
            Object element = change.getModifiedElement();
            if (element instanceof ICompilationUnit) {
                cus.add((ICompilationUnit) element);
            }
        }
    }

    protected boolean isFinalConditionErrorBlocking() {
        return true;
    }

    // Same logic as JDT.LS PreferenceManager.getCodeGenerationSettings(ICompilationUnit)
    protected static CodeGenerationSettings createCodeGenerationSettings(ICompilationUnit cu) {
        CodeGenerationSettings settings = new CodeGenerationSettings();
        settings.overrideAnnotation = true;
        settings.createComments = false;
        settings.tabWidth = CodeFormatterUtil.getTabWidth(cu);
        settings.indentWidth = CodeFormatterUtil.getIndentWidth(cu);
        return settings;
    }

    private static String getRuntimeExceptionMessage(RuntimeException e) {
        StringBuilder sb = new StringBuilder();
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            sb.append("unexpected ").append(e.getClass().getSimpleName());
        } else {
            sb.append(message);
        }
        sb.append(" | stacktrace: ");
        StackTraceElement[] stack = e.getStackTrace();
        int limit = Math.min(stack.length, 15);
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" <- ");
            sb.append(stack[i].toString());
        }
        return sb.toString();
    }

}
