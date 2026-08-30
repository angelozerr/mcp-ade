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
package com.ibm.mcp.languagetools.lsp.client;

/**
 * LSP capability enum.
 */
public enum LspCapability {

    REFERENCES(LspRequestConstants.TEXT_DOCUMENT_REFERENCES, "referencesProvider"),
    DEFINITION(LspRequestConstants.TEXT_DOCUMENT_DEFINITION, "definitionProvider"),
    DECLARATION(LspRequestConstants.TEXT_DOCUMENT_DECLARATION, "declarationProvider"),
    IMPLEMENTATION(LspRequestConstants.TEXT_DOCUMENT_IMPLEMENTATION, "implementationProvider"),
    DIAGNOSTIC(LspRequestConstants.TEXT_DOCUMENT_DIAGNOSTIC, "diagnosticProvider"),
    HOVER(LspRequestConstants.TEXT_DOCUMENT_HOVER, "hoverProvider"),
    COMPLETION(LspRequestConstants.TEXT_DOCUMENT_COMPLETION, "completionProvider"),
    DOCUMENT_SYMBOL(LspRequestConstants.TEXT_DOCUMENT_DOCUMENT_SYMBOL, "documentSymbolProvider"),
    CODE_ACTION(LspRequestConstants.TEXT_DOCUMENT_CODE_ACTION, "codeActionProvider"),
    RENAME(LspRequestConstants.TEXT_DOCUMENT_RENAME, "renameProvider"),
    TYPE_DEFINITION(LspRequestConstants.TEXT_DOCUMENT_TYPE_DEFINITION, "typeDefinitionProvider"),
    FORMATTING(LspRequestConstants.TEXT_DOCUMENT_FORMATTING, "documentFormattingProvider"),
    RANGE_FORMATTING(LspRequestConstants.TEXT_DOCUMENT_RANGE_FORMATTING, "documentRangeFormattingProvider"),
    SIGNATURE_HELP(LspRequestConstants.TEXT_DOCUMENT_SIGNATURE_HELP, "signatureHelpProvider"),
    CODE_LENS(LspRequestConstants.TEXT_DOCUMENT_CODE_LENS, "codeLensProvider"),
    INLAY_HINT(LspRequestConstants.TEXT_DOCUMENT_INLAY_HINT, "inlayHintProvider"),
    CALL_HIERARCHY(LspRequestConstants.TEXT_DOCUMENT_PREPARE_CALL_HIERARCHY, "callHierarchyProvider"),
    TYPE_HIERARCHY(LspRequestConstants.TEXT_DOCUMENT_PREPARE_TYPE_HIERARCHY, "typeHierarchyProvider"),
    WORKSPACE_SYMBOL(LspRequestConstants.WORKSPACE_SYMBOL, "workspaceSymbolProvider");

    private final String method;
    private final String capabilityKey;

    LspCapability(String method, String capabilityKey) {
        this.method = method;
        this.capabilityKey = capabilityKey;
    }

    public String getMethod() {
        return method;
    }

    public String getCapabilityKey() {
        return capabilityKey;
    }
}
