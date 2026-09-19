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
package org.eclipse.mcp.ade.lsp.client;

/**
 * LSP capability enum.
 */
public enum LspCapability {

    REFERENCES(LspRequestConstants.TEXT_DOCUMENT_REFERENCES, "referencesProvider", false),
    DEFINITION(LspRequestConstants.TEXT_DOCUMENT_DEFINITION, "definitionProvider", false),
    DECLARATION(LspRequestConstants.TEXT_DOCUMENT_DECLARATION, "declarationProvider", false),
    IMPLEMENTATION(LspRequestConstants.TEXT_DOCUMENT_IMPLEMENTATION, "implementationProvider", false),
    DIAGNOSTIC(LspRequestConstants.TEXT_DOCUMENT_DIAGNOSTIC, "diagnosticProvider", true),
    HOVER(LspRequestConstants.TEXT_DOCUMENT_HOVER, "hoverProvider", false),
    COMPLETION(LspRequestConstants.TEXT_DOCUMENT_COMPLETION, "completionProvider", false),
    DOCUMENT_SYMBOL(LspRequestConstants.TEXT_DOCUMENT_DOCUMENT_SYMBOL, "documentSymbolProvider", false),
    CODE_ACTION(LspRequestConstants.TEXT_DOCUMENT_CODE_ACTION, "codeActionProvider", true),
    RENAME(LspRequestConstants.TEXT_DOCUMENT_RENAME, "renameProvider", false),
    TYPE_DEFINITION(LspRequestConstants.TEXT_DOCUMENT_TYPE_DEFINITION, "typeDefinitionProvider", false),
    FORMATTING(LspRequestConstants.TEXT_DOCUMENT_FORMATTING, "documentFormattingProvider", false),
    RANGE_FORMATTING(LspRequestConstants.TEXT_DOCUMENT_RANGE_FORMATTING, "documentRangeFormattingProvider", false),
    SIGNATURE_HELP(LspRequestConstants.TEXT_DOCUMENT_SIGNATURE_HELP, "signatureHelpProvider", false),
    CODE_LENS(LspRequestConstants.TEXT_DOCUMENT_CODE_LENS, "codeLensProvider", false),
    INLAY_HINT(LspRequestConstants.TEXT_DOCUMENT_INLAY_HINT, "inlayHintProvider", false),
    CALL_HIERARCHY(LspRequestConstants.TEXT_DOCUMENT_PREPARE_CALL_HIERARCHY, "callHierarchyProvider", false),
    TYPE_HIERARCHY(LspRequestConstants.TEXT_DOCUMENT_PREPARE_TYPE_HIERARCHY, "typeHierarchyProvider", false),
    WORKSPACE_SYMBOL(LspRequestConstants.WORKSPACE_SYMBOL, "workspaceSymbolProvider", false);

    private final String method;
    private final String capabilityKey;
    private final boolean needsDiagnosticsWait;

    LspCapability(String method, String capabilityKey, boolean needsDiagnosticsWait) {
        this.method = method;
        this.capabilityKey = capabilityKey;
        this.needsDiagnosticsWait = needsDiagnosticsWait;
    }

    public String getMethod() {
        return method;
    }

    public String getCapabilityKey() {
        return capabilityKey;
    }

    /**
     * Whether this capability requires waiting for publishDiagnostics after didOpen.
     * Only DIAGNOSTIC and CODE_ACTION need the full diagnostics cycle;
     * other capabilities (hover, definition, references, etc.) can execute
     * immediately after didOpen since LSP processes messages in order.
     */
    public boolean needsDiagnosticsWait() {
        return needsDiagnosticsWait;
    }
}
