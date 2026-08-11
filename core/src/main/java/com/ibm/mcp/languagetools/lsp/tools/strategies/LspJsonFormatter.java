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
import com.ibm.mcp.languagetools.utils.UriUtils;
import org.eclipse.lsp4j.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LspJsonFormatter {

    public static final String EMPTY_ARRAY = "[]";

    private static final Gson GSON = new Gson();

    private LspJsonFormatter() {
    }

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static String range(Range range) {
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
            Object value = unwrapEither(keysAndValues[i + 1]);
            if (value != null) {
                result.put((String) keysAndValues[i], value);
            }
        }
        return result;
    }

    private static Object unwrapEither(Object value) {
        if (value instanceof org.eclipse.lsp4j.jsonrpc.messages.Either<?, ?> e) {
            return e.isLeft() ? e.getLeft() : e.getRight();
        }
        return value;
    }

    // --- URI compaction (delegates to UriUtils) ---

    public static String cwdToUriPrefix(String cwd) {
        return UriUtils.cwdToUriPrefix(cwd);
    }

    public static String compactUri(String uri, String cwdUri) {
        return UriUtils.compactUri(uri, cwdUri);
    }

    // --- Locations ---

    static Map<String, Object> location(Location loc, String cwdUri) {
        Map<String, Object> result = new LinkedHashMap<>(UriUtils.compactUriToMap(loc.getUri(), cwdUri));
        result.put("range", range(loc.getRange()));
        return result;
    }

    static List<Map<String, Object>> locationsByFile(List<? extends Location> locations, String cwdUri) {
        Map<String, LocationGroup> grouped = new LinkedHashMap<>();
        for (Location loc : locations) {
            Map<String, String> compact = UriUtils.compactUriToMap(loc.getUri(), cwdUri);
            var it = compact.entrySet().iterator();
            var first = it.next();
            String groupKey = first.getKey() + "=" + first.getValue();

            LocationGroup group = grouped.computeIfAbsent(groupKey, k -> {
                Map<String, String> gf = new LinkedHashMap<>();
                gf.put(first.getKey(), first.getValue());
                return new LocationGroup(gf);
            });

            if (it.hasNext()) {
                Map<String, String> itemFields = new LinkedHashMap<>();
                while (it.hasNext()) {
                    var e = it.next();
                    itemFields.put(e.getKey(), e.getValue());
                }
                group.addItem(itemFields, range(loc.getRange()));
            } else {
                group.ranges.add(range(loc.getRange()));
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (LocationGroup group : grouped.values()) {
            Map<String, Object> entry = new LinkedHashMap<>(group.fields);
            if (!group.items.isEmpty()) {
                entry.put("items", group.items);
            } else {
                entry.put("refs", group.ranges);
            }
            result.add(entry);
        }
        return result;
    }

    private static class LocationGroup {
        final Map<String, String> fields;
        final List<String> ranges = new ArrayList<>();
        final List<Map<String, String>> items = new ArrayList<>();
        LocationGroup(Map<String, String> fields) {
            this.fields = fields;
        }
        void addItem(Map<String, String> extra, String range) {
            Map<String, String> item = new LinkedHashMap<>(extra);
            item.put("range", range);
            items.add(item);
        }
    }

    // --- TextEdits ---

    static Map<String, Object> textEdit(TextEdit te) {
        return map(
                "range", range(te.getRange()),
                "newText", te.getNewText()
        );
    }

    public static Map<String, Object> textEditsResult(List<TextEdit> edits, boolean applied) {
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
                "code", d.getCode()
        );
    }

    // --- Symbols ---

    static Map<String, Object> symbolInfo(SymbolInformation sym, String cwdUri) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", sym.getName());
        if (sym.getKind() != null) result.put("kind", sym.getKind().name());
        if (sym.getContainerName() != null) result.put("containerName", sym.getContainerName());
        if (sym.getLocation() != null) {
            result.putAll(UriUtils.compactUriToMap(sym.getLocation().getUri(), cwdUri));
            result.put("range", range(sym.getLocation().getRange()));
        }
        return result;
    }

    static Map<String, Object> documentSymbol(DocumentSymbol sym) {
        return map(
                "name", sym.getName(),
                "kind", sym.getKind() != null ? sym.getKind().name() : null,
                "detail", sym.getDetail(),
                "range", range(sym.getRange()),
                "children", sym.getChildren() != null && !sym.getChildren().isEmpty()
                        ? sym.getChildren().stream().map(LspJsonFormatter::documentSymbol).toList()
                        : null
        );
    }

    // --- Completions ---

    static Map<String, Object> completionItem(CompletionItem item) {
        String detail = item.getDetail();
        if (detail != null && detail.equals(item.getLabel())) {
            detail = null;
        }
        return map(
                "label", item.getLabel(),
                "kind", item.getKind() != null ? item.getKind().name() : null,
                "detail", detail
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

    static List<Map<String, Object>> workspaceEdits(List<WorkspaceEdit> edits, String cwdUri) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (WorkspaceEdit edit : edits) {
            if (edit.getChanges() != null) {
                for (Map.Entry<String, List<TextEdit>> entry : edit.getChanges().entrySet()) {
                    Map<String, String> compact = UriUtils.compactUriToMap(entry.getKey(), cwdUri);
                    for (TextEdit te : entry.getValue()) {
                        Map<String, Object> item = new LinkedHashMap<>(compact);
                        item.put("range", range(te.getRange()));
                        item.put("newText", te.getNewText());
                        result.add(item);
                    }
                }
            }
            if (edit.getDocumentChanges() != null) {
                for (var change : edit.getDocumentChanges()) {
                    if (change.isLeft()) {
                        TextDocumentEdit docEdit = change.getLeft();
                        Map<String, String> compact = UriUtils.compactUriToMap(docEdit.getTextDocument().getUri(), cwdUri);
                        for (var textEdit : docEdit.getEdits()) {
                            if (!textEdit.isLeft()) continue;
                            TextEdit te = textEdit.getLeft();
                            Map<String, Object> item = new LinkedHashMap<>(compact);
                            item.put("range", range(te.getRange()));
                            item.put("newText", te.getNewText());
                            result.add(item);
                        }
                    } else {
                        result.add(map("resourceOperation", change.getRight().getKind()));
                    }
                }
            }
        }
        return result;
    }

    // --- CallHierarchy ---

    static Map<String, Object> callHierarchyItem(CallHierarchyItem item, String cwdUri) {
        Map<String, Object> result = new LinkedHashMap<>(UriUtils.compactUriToMap(item.getUri(), cwdUri));
        result.put("name", item.getName());
        if (item.getKind() != null) result.put("kind", item.getKind().name());
        if (item.getDetail() != null) result.put("detail", item.getDetail());
        result.put("range", range(item.getRange()));
        result.put("selectionRange", range(item.getSelectionRange()));
        return result;
    }

    static Map<String, Object> callHierarchyIncomingCall(CallHierarchyIncomingCall call, String cwdUri) {
        Map<String, Object> result = callHierarchyItem(call.getFrom(), cwdUri);
        if (call.getFromRanges() != null && !call.getFromRanges().isEmpty()) {
            result.put("fromRanges", call.getFromRanges().stream().map(LspJsonFormatter::range).toList());
        }
        return result;
    }

    static Map<String, Object> callHierarchyOutgoingCall(CallHierarchyOutgoingCall call, String cwdUri) {
        Map<String, Object> result = callHierarchyItem(call.getTo(), cwdUri);
        if (call.getFromRanges() != null && !call.getFromRanges().isEmpty()) {
            result.put("fromRanges", call.getFromRanges().stream().map(LspJsonFormatter::range).toList());
        }
        return result;
    }

    // --- TypeHierarchy ---

    static Map<String, Object> typeHierarchyItem(TypeHierarchyItem item, String cwdUri) {
        Map<String, Object> result = new LinkedHashMap<>(UriUtils.compactUriToMap(item.getUri(), cwdUri));
        result.put("name", item.getName());
        if (item.getKind() != null) result.put("kind", item.getKind().name());
        if (item.getDetail() != null) result.put("detail", item.getDetail());
        result.put("range", range(item.getRange()));
        result.put("selectionRange", range(item.getSelectionRange()));
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
