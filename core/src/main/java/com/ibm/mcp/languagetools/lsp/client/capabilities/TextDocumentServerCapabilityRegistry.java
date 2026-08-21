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
import com.ibm.mcp.languagetools.language.PathPatternMatcher;
import com.ibm.mcp.languagetools.utils.JsonUtils;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentRegistrationOptions;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Base class for Server capability registry for 'textDocument/*'.
 *
 * @param <T> the LSP {@link TextDocumentRegistrationOptions}.
 */
public class TextDocumentServerCapabilityRegistry<T extends TextDocumentRegistrationOptions> {

    private final LspClientFeatures clientFeatures;
    private final Predicate<ServerCapabilities> serverCapabilitiesPredicate;
    private final Class<T> optionsClass;
    private ServerCapabilities serverCapabilities;
    private final List<T> dynamicCapabilities;
    private final Map<T, ExtendedDocumentSelector> documentSelectorCache;

    public TextDocumentServerCapabilityRegistry(LspClientFeatures clientFeatures,
                                                Predicate<ServerCapabilities> serverCapabilitiesPredicate,
                                                Class<T> optionsClass) {
        this.clientFeatures = clientFeatures;
        this.serverCapabilitiesPredicate = serverCapabilitiesPredicate;
        this.optionsClass = optionsClass;
        this.dynamicCapabilities = new CopyOnWriteArrayList<>();
        this.documentSelectorCache = new IdentityHashMap<>();
    }

    public void setServerCapabilities(ServerCapabilities serverCapabilities) {
        this.serverCapabilities = serverCapabilities;
        this.dynamicCapabilities.clear();
        this.documentSelectorCache.clear();
    }

    public ServerCapabilities getServerCapabilities() {
        return serverCapabilities;
    }

    public T registerCapability(JsonObject registerOptions) {
        T t = create(registerOptions);
        if (t != null) {
            synchronized (dynamicCapabilities) {
                dynamicCapabilities.add(t);
            }
        }
        return t;
    }

    protected T create(JsonObject registerOptions) {
        return JsonUtils.getLsp4jGson().fromJson(registerOptions, optionsClass);
    }

    public void unregisterCapability(Object options) {
        dynamicCapabilities.remove(options);
        documentSelectorCache.remove(options);
    }

    /**
     * Returns true if the language server supports this capability for the given document.
     *
     * @param document the language document.
     * @return true if the language server supports this capability and false otherwise.
     */
    public boolean isSupported(LanguageDocument document) {
        return isSupported(document, serverCapabilitiesPredicate);
    }

    protected boolean isSupported(LanguageDocument document,
                                  Predicate<ServerCapabilities> matchServerCapabilities) {
        return isSupported(document, matchServerCapabilities, null);
    }

    protected boolean isSupported(LanguageDocument document,
                                  Predicate<ServerCapabilities> matchServerCapabilities,
                                  Predicate<T> matchOption) {
        // Check static server capabilities
        if (serverCapabilities != null && matchServerCapabilities.test(serverCapabilities)) {
            return true;
        }

        // Check dynamic capabilities
        if (dynamicCapabilities.isEmpty()) {
            return false;
        }

        String languageId = document.getLanguageId();
        String scheme = null;

        for (var option : dynamicCapabilities) {
            // Match documentSelector?
            var selector = documentSelectorCache.computeIfAbsent(option,
                    o -> new ExtendedDocumentSelector(o.getDocumentSelector()));
            var filters = selector.getFilters();
            if (filters.isEmpty()) {
                return matchOption != null ? matchOption.test(option) : true;
            }

            for (var filter : filters) {
                boolean hasLanguage = filter.getLanguage() != null && !filter.getLanguage().isEmpty();
                boolean hasScheme = filter.getScheme() != null && !filter.getScheme().isEmpty();
                var pattern = filter.getPattern();
                boolean hasPattern = pattern != null && (pattern.isLeft() ? !pattern.getLeft().isEmpty() : true);

                boolean matchDocumentSelector = false;

                // Matches language?
                if (hasLanguage) {
                    matchDocumentSelector = (languageId == null && !hasScheme && !hasPattern)
                            || filter.getLanguage().equals(languageId);
                }

                if (!matchDocumentSelector) {
                    // Matches scheme?
                    if (hasScheme) {
                        if (scheme == null) {
                            scheme = document.getScheme();
                        }
                        matchDocumentSelector = filter.getScheme().equals(scheme);
                    }

                    if (!matchDocumentSelector) {
                        // Matches pattern?
                        if (hasPattern) {
                            PathPatternMatcher patternMatcher = filter.getPathPattern();
                            matchDocumentSelector = patternMatcher.matches(document.getUri());
                        }
                    }
                }

                if (matchDocumentSelector) {
                    if (matchOption == null) {
                        return true;
                    }
                    if (matchOption.test(option)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean hasCapability(final Either<Boolean, ?> eitherCapability) {
        if (eitherCapability == null) {
            return false;
        }
        return eitherCapability.isRight() || hasCapability(eitherCapability.getLeft());
    }

    public static boolean hasCapability(Boolean capability) {
        return capability != null && capability;
    }

    public List<T> getOptions() {
        return dynamicCapabilities;
    }

    protected LspClientFeatures getClientFeatures() {
        return clientFeatures;
    }
}
