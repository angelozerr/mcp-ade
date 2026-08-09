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
package com.ibm.mcp.languagetools.lsp.client.capabilities;

import com.ibm.mcp.languagetools.language.LanguageDocument;
import com.ibm.mcp.languagetools.lsp.client.LspClientFeatures;
import org.eclipse.lsp4j.ReferenceRegistrationOptions;

/**
 * Server capability registry for 'textDocument/references'.
 */
public class ReferencesCapabilityRegistry extends TextDocumentServerCapabilityRegistry<ReferenceRegistrationOptions> {

    public ReferencesCapabilityRegistry(LspClientFeatures clientFeatures) {
        super(clientFeatures, sc -> hasCapability(sc.getReferencesProvider()), ReferenceRegistrationOptions.class);
    }

    /**
     * Returns true if the language server can support references and false otherwise.
     *
     * @param document the language document.
     * @return true if the language server can support references and false otherwise.
     */
    public boolean isReferencesSupported(LanguageDocument document) {
        return super.isSupported(document);
    }
}
