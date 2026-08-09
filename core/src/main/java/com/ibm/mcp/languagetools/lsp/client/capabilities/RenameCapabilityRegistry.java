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
import org.eclipse.lsp4j.RenameOptions;

public class RenameCapabilityRegistry extends TextDocumentServerCapabilityRegistry<RenameOptions> {

    public RenameCapabilityRegistry(LspClientFeatures clientFeatures) {
        super(clientFeatures, sc -> hasCapability(sc.getRenameProvider()), RenameOptions.class);
    }

    public boolean isRenameSupported(LanguageDocument document) {
        return super.isSupported(document);
    }
}
