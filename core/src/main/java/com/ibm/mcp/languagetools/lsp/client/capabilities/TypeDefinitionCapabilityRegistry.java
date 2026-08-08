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

import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.language.LanguageDocument;
import com.ibm.mcp.languagetools.lsp.client.LspClientFeatures;
import com.ibm.mcp.languagetools.utils.JsonUtils;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TypeDefinitionRegistrationOptions;

import java.util.List;
import java.util.function.Predicate;

public class TypeDefinitionCapabilityRegistry extends TextDocumentServerCapabilityRegistry<TypeDefinitionRegistrationOptions> {

    private static final Predicate<ServerCapabilities> SERVER_CAPABILITIES_PREDICATE = sc ->
            hasCapability(sc.getTypeDefinitionProvider());

    public TypeDefinitionCapabilityRegistry(LspClientFeatures clientFeatures) {
        super(clientFeatures);
    }

    static class ExtendedTypeDefinitionRegistrationOptions extends TypeDefinitionRegistrationOptions implements ExtendedDocumentSelector.DocumentFilersProvider {
        private transient ExtendedDocumentSelector documentSelector;

        @Override
        public List<ExtendedDocumentSelector.ExtendedDocumentFilter> getFilters() {
            if (documentSelector == null) {
                documentSelector = new ExtendedDocumentSelector(super.getDocumentSelector());
            }
            return documentSelector.getFilters();
        }
    }

    @Override
    protected TypeDefinitionRegistrationOptions create(JsonObject registerOptions) {
        return JsonUtils.getLsp4jGson().fromJson(registerOptions, ExtendedTypeDefinitionRegistrationOptions.class);
    }

    public boolean isTypeDefinitionSupported(LanguageDocument document) {
        return super.isSupported(document, SERVER_CAPABILITIES_PREDICATE);
    }
}
