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
import org.eclipse.lsp4j.SignatureHelpRegistrationOptions;

public class SignatureHelpCapabilityRegistry extends TextDocumentServerCapabilityRegistry<SignatureHelpRegistrationOptions> {

    public SignatureHelpCapabilityRegistry(LspClientFeatures clientFeatures) {
        super(clientFeatures, sc -> sc.getSignatureHelpProvider() != null, SignatureHelpRegistrationOptions.class);
    }

    public boolean isSignatureHelpSupported(LanguageDocument document) {
        return super.isSupported(document);
    }
}
