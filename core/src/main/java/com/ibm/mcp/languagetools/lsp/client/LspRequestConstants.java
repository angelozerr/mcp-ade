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
 * LSP request constants.
 */
public class LspRequestConstants {

    // textDocument/* LSP requests
    public static final String TEXT_DOCUMENT_REFERENCES = "textDocument/references";
    public static final String TEXT_DOCUMENT_DEFINITION = "textDocument/definition";
    public static final String TEXT_DOCUMENT_DECLARATION = "textDocument/declaration";
    public static final String TEXT_DOCUMENT_IMPLEMENTATION = "textDocument/implementation";
    public static final String TEXT_DOCUMENT_DIAGNOSTIC = "textDocument/diagnostic";
    public static final String TEXT_DOCUMENT_HOVER = "textDocument/hover";
    public static final String TEXT_DOCUMENT_COMPLETION = "textDocument/completion";
    public static final String TEXT_DOCUMENT_DOCUMENT_SYMBOL = "textDocument/documentSymbol";
    public static final String TEXT_DOCUMENT_CODE_ACTION = "textDocument/codeAction";
    public static final String TEXT_DOCUMENT_RENAME = "textDocument/rename";
    public static final String TEXT_DOCUMENT_TYPE_DEFINITION = "textDocument/typeDefinition";
    public static final String TEXT_DOCUMENT_FORMATTING = "textDocument/formatting";
    public static final String TEXT_DOCUMENT_RANGE_FORMATTING = "textDocument/rangeFormatting";
    public static final String TEXT_DOCUMENT_SIGNATURE_HELP = "textDocument/signatureHelp";
    public static final String TEXT_DOCUMENT_CODE_LENS = "textDocument/codeLens";
    public static final String TEXT_DOCUMENT_INLAY_HINT = "textDocument/inlayHint";

    // workspace/* LSP requests
    public static final String WORKSPACE_SYMBOL = "workspace/symbol";
    public static final String WORKSPACE_DID_CHANGE_WATCHED_FILES = "workspace/didChangeWatchedFiles";

    private LspRequestConstants() {
    }
}
