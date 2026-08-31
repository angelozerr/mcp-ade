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
package org.eclipse.mcp.ade.lsp.tools.strategies;

import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.client.LspCapability;
import org.eclipse.mcp.ade.lsp.tools.params.FilePositionRequestParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Base class for location-based LSP request strategies that return
 * {@code Either<List<? extends Location>, List<? extends LocationLink>>}.
 * <p>
 * Provides shared implementations of {@link #getEmptyResult()}, {@link #isValidResult},
 * {@link #formatResults}, and {@link #formatNoResultFound} used by definition,
 * declaration, type-definition, and implementation strategies.
 *
 * @param <TLspParams> LSP request parameters type
 */
public abstract class LocationBasedStrategy<TLspParams>
        extends FilePositionBasedStrategy<TLspParams, Either<List<? extends Location>, List<? extends LocationLink>>> {

    protected LocationBasedStrategy(LanguageRegistry languageRegistry, LspCapability capability, String title) {
        super(languageRegistry, capability, title);
    }

    protected LocationBasedStrategy(LanguageRegistry languageRegistry, LspCapability capability, String title, Supplier<TLspParams> paramsFactory) {
        super(languageRegistry, capability, title, paramsFactory);
    }

    @Override
    public Either<List<? extends Location>, List<? extends LocationLink>> getEmptyResult() {
        return Either.forLeft(Collections.emptyList());
    }

    @Override
    public boolean isValidResult(Either<List<? extends Location>, List<? extends LocationLink>> result) {
        if (result == null) return false;
        if (result.isLeft()) return !result.getLeft().isEmpty();
        if (result.isRight()) return !result.getRight().isEmpty();
        return false;
    }

    @Override
    public String formatResults(FilePositionRequestParams params, List<Either<List<? extends Location>, List<? extends LocationLink>>> results) {
        // Merge all locations from all results
        List<Location> allLocations = results.stream()
                .flatMap(either -> {
                    if (either.isLeft()) {
                        return either.getLeft().stream();
                    } else {
                        // Convert LocationLink to Location
                        return either.getRight().stream()
                                .map(link -> new Location(link.getTargetUri(), link.getTargetRange()));
                    }
                })
                .distinct()
                .toList();

        if (allLocations.isEmpty()) {
            return formatNoResultFound(params);
        }

        String cwdUri = LspJsonFormatter.cwdToUriPrefix(params.getCwd());
        return LspJsonFormatter.toJson(LspJsonFormatter.locationsByFile(allLocations, cwdUri));
    }

    @Override
    public String formatNoResultFound(FilePositionRequestParams params) {
        return LspJsonFormatter.EMPTY_ARRAY;
    }
}
