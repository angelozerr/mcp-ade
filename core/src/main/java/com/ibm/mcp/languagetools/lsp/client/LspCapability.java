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

    REFERENCES(LspRequestConstants.TEXT_DOCUMENT_REFERENCES),
    DEFINITION(LspRequestConstants.TEXT_DOCUMENT_DEFINITION),
    DECLARATION(LspRequestConstants.TEXT_DOCUMENT_DECLARATION),
    IMPLEMENTATION(LspRequestConstants.TEXT_DOCUMENT_IMPLEMENTATION),
    DIAGNOSTIC(LspRequestConstants.TEXT_DOCUMENT_DIAGNOSTIC),
    HOVER(LspRequestConstants.TEXT_DOCUMENT_HOVER),
    COMPLETION(LspRequestConstants.TEXT_DOCUMENT_COMPLETION),
    DOCUMENT_SYMBOL(LspRequestConstants.TEXT_DOCUMENT_DOCUMENT_SYMBOL),
    CODE_ACTION(LspRequestConstants.TEXT_DOCUMENT_CODE_ACTION),
    RENAME(LspRequestConstants.TEXT_DOCUMENT_RENAME),
    TYPE_DEFINITION(LspRequestConstants.TEXT_DOCUMENT_TYPE_DEFINITION),
    FORMATTING(LspRequestConstants.TEXT_DOCUMENT_FORMATTING),
    RANGE_FORMATTING(LspRequestConstants.TEXT_DOCUMENT_RANGE_FORMATTING),
    SIGNATURE_HELP(LspRequestConstants.TEXT_DOCUMENT_SIGNATURE_HELP),
    CODE_LENS(LspRequestConstants.TEXT_DOCUMENT_CODE_LENS),
    INLAY_HINT(LspRequestConstants.TEXT_DOCUMENT_INLAY_HINT),
    CALL_HIERARCHY(LspRequestConstants.TEXT_DOCUMENT_PREPARE_CALL_HIERARCHY),
    TYPE_HIERARCHY(LspRequestConstants.TEXT_DOCUMENT_PREPARE_TYPE_HIERARCHY),
    WORKSPACE_SYMBOL(LspRequestConstants.WORKSPACE_SYMBOL);

    private final String method;

    LspCapability(String method) {
        this.method = method;
    }

    public String getMethod() {
        return method;
    }
}
