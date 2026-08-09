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
import org.eclipse.lsp4j.InlayHintRegistrationOptions;

public class InlayHintCapabilityRegistry extends TextDocumentServerCapabilityRegistry<InlayHintRegistrationOptions> {

    public InlayHintCapabilityRegistry(LspClientFeatures clientFeatures) {
        super(clientFeatures, sc -> hasCapability(sc.getInlayHintProvider()), InlayHintRegistrationOptions.class);
    }

    public boolean isInlayHintSupported(LanguageDocument document) {
        return super.isSupported(document);
    }
}
