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
package com.ibm.mcp.languagetools.lsp.tools.strategies;

import com.google.gson.Gson;
import org.eclipse.lsp4j.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LspJsonFormatter {

    static final String EMPTY_ARRAY = "[]";

    private static final Gson GSON = new Gson();

    private LspJsonFormatter() {
    }

    static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    static String range(Range range) {
        return (range.getStart().getLine() + 1) + ":" + range.getStart().getCharacter()
                + "-" + (range.getEnd().getLine() + 1) + ":" + range.getEnd().getCharacter();
    }

    static String position(Position pos) {
        return (pos.getLine() + 1) + ":" + pos.getCharacter();
    }

    // --- Map builder that skips null values ---

    static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            Object value = keysAndValues[i + 1];
            if (value != null) {
                result.put((String) keysAndValues[i], value);
            }
        }
        return result;
    }

    // --- Locations ---

    static Map<String, Object> location(Location loc) {
        return map(
                "uri", loc.getUri(),
                "range", range(loc.getRange())
        );
    }

    // --- TextEdits ---

    static Map<String, Object> textEdit(TextEdit te) {
        return map(
                "range", range(te.getRange()),
                "newText", te.getNewText()
        );
    }

    static Map<String, Object> textEditsResult(List<TextEdit> edits, boolean applied) {
        return map(
                "applied", applied,
                "edits", edits.stream().map(LspJsonFormatter::textEdit).toList()
        );
    }

    // --- Diagnostics ---

    static Map<String, Object> diagnostic(Diagnostic d) {
        return map(
                "range", range(d.getRange()),
                "severity", d.getSeverity() != null ? d.getSeverity().name() : null,
                "message", d.getMessage(),
                "source", d.getSource(),
                "code", d.getCode() != null
                        ? (d.getCode().isLeft() ? d.getCode().getLeft() : d.getCode().getRight())
                        : null
        );
    }

    // --- Symbols ---

    static Map<String, Object> symbolInfo(SymbolInformation sym) {
        return map(
                "name", sym.getName(),
                "kind", sym.getKind() != null ? sym.getKind().name() : null,
                "containerName", sym.getContainerName(),
                "uri", sym.getLocation() != null ? sym.getLocation().getUri() : null,
                "range", sym.getLocation() != null ? range(sym.getLocation().getRange()) : null
        );
    }

    static Map<String, Object> documentSymbol(DocumentSymbol sym) {
        return map(
                "name", sym.getName(),
                "kind", sym.getKind() != null ? sym.getKind().name() : null,
                "detail", sym.getDetail(),
                "range", range(sym.getRange()),
                "selectionRange", range(sym.getSelectionRange()),
                "children", sym.getChildren() != null && !sym.getChildren().isEmpty()
                        ? sym.getChildren().stream().map(LspJsonFormatter::documentSymbol).toList()
                        : null
        );
    }

    // --- Completions ---

    static Map<String, Object> completionItem(CompletionItem item) {
        return map(
                "label", item.getLabel(),
                "kind", item.getKind() != null ? item.getKind().name() : null,
                "detail", item.getDetail(),
                "sortText", item.getSortText()
        );
    }

    // --- InlayHints ---

    static Map<String, Object> inlayHint(InlayHint hint) {
        return map(
                "position", position(hint.getPosition()),
                "kind", hint.getKind() != null ? hint.getKind().name() : null,
                "label", extractInlayLabel(hint.getLabel())
        );
    }

    // --- CodeLens ---

    static Map<String, Object> codeLens(CodeLens cl) {
        return map(
                "range", range(cl.getRange()),
                "title", cl.getCommand() != null ? cl.getCommand().getTitle() : null,
                "command", cl.getCommand() != null ? cl.getCommand().getCommand() : null
        );
    }

    // --- Hover ---

    static Map<String, Object> hover(List<String> contents) {
        if (contents.size() == 1) {
            return map("contents", contents.get(0));
        }
        return map("contents", contents);
    }

    // --- SignatureHelp ---

    static Map<String, Object> signatureInfo(SignatureInformation sig, Integer activeParameter) {
        Map<String, Object> result = map(
                "label", sig.getLabel(),
                "documentation", sig.getDocumentation() != null ? extractDocumentation(sig.getDocumentation()) : null
        );
        if (sig.getParameters() != null && !sig.getParameters().isEmpty()) {
            List<Map<String, Object>> params = new java.util.ArrayList<>();
            for (int i = 0; i < sig.getParameters().size(); i++) {
                ParameterInformation param = sig.getParameters().get(i);
                Map<String, Object> p = map(
                        "label", extractParamLabel(param),
                        "documentation", param.getDocumentation() != null ? extractDocumentation(param.getDocumentation()) : null,
                        "active", activeParameter != null && i == activeParameter ? true : null
                );
                params.add(p);
            }
            result.put("parameters", params);
        }
        return result;
    }

    // --- CodeAction ---

    static Map<String, Object> codeAction(CodeAction action) {
        Map<String, Object> result = map(
                "title", action.getTitle(),
                "kind", action.getKind()
        );
        if (action.getDiagnostics() != null && !action.getDiagnostics().isEmpty()) {
            result.put("diagnostics", action.getDiagnostics().stream()
                    .map(d -> map("message", d.getMessage(), "line", d.getRange().getStart().getLine() + 1))
                    .toList());
        }
        return result;
    }

    static Map<String, Object> command(Command cmd) {
        return map(
                "title", cmd.getTitle(),
                "command", cmd.getCommand()
        );
    }

    // --- WorkspaceEdit (rename) ---

    static List<Map<String, Object>> workspaceEdits(List<WorkspaceEdit> edits) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (WorkspaceEdit edit : edits) {
            if (edit.getChanges() != null) {
                for (Map.Entry<String, List<TextEdit>> entry : edit.getChanges().entrySet()) {
                    for (TextEdit te : entry.getValue()) {
                        result.add(map(
                                "uri", entry.getKey(),
                                "range", range(te.getRange()),
                                "newText", te.getNewText()
                        ));
                    }
                }
            }
            if (edit.getDocumentChanges() != null) {
                for (var change : edit.getDocumentChanges()) {
                    if (change.isLeft()) {
                        TextDocumentEdit docEdit = change.getLeft();
                        String uri = docEdit.getTextDocument().getUri();
                        for (var textEdit : docEdit.getEdits()) {
                            if (!textEdit.isLeft()) continue;
                            TextEdit te = textEdit.getLeft();
                            result.add(map(
                                    "uri", uri,
                                    "range", range(te.getRange()),
                                    "newText", te.getNewText()
                            ));
                        }
                    } else {
                        result.add(map("resourceOperation", change.getRight().getKind()));
                    }
                }
            }
        }
        return result;
    }

    // --- Helpers ---

    @SuppressWarnings("unchecked")
    private static String extractInlayLabel(Object label) {
        if (label instanceof String s) return s;
        if (label instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof InlayHintLabelPart p) {
                    sb.append(p.getValue());
                }
            }
            return sb.toString();
        }
        return String.valueOf(label);
    }

    private static String extractDocumentation(Object documentation) {
        if (documentation instanceof String s) return s;
        if (documentation instanceof MarkupContent mc) return mc.getValue();
        return null;
    }

    private static String extractParamLabel(ParameterInformation param) {
        if (param.getLabel().isLeft()) return param.getLabel().getLeft();
        var tuple = param.getLabel().getRight();
        return "[" + tuple.getFirst() + "," + tuple.getSecond() + "]";
    }
}
