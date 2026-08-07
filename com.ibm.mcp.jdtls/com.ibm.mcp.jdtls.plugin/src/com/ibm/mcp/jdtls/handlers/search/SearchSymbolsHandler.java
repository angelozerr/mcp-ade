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
package com.ibm.mcp.jdtls.handlers.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.searchSymbols" command.
 *
 * <p>Arguments: [{query, kind}] where kind is optional ("type", "method",
 * "field").</p>
 *
 * <p>For types, uses {@link SearchEngine#searchAllTypeNames}. For methods and
 * fields, uses {@link SearchPattern} with a string pattern and
 * {@link IJavaSearchConstants#DECLARATIONS}.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/SearchSymbolsTool.java">javalens-mcp SearchSymbolsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class SearchSymbolsHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }
        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String query = (String) params.get("query");
        if (query == null || query.isBlank()) {
            throw new RuntimeException("Missing query parameter");
        }
        String kind = (String) params.get("kind");

        List<Map<String, Object>> symbols = new ArrayList<>();
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        SearchEngine engine = new SearchEngine();

        if (kind == null || "type".equals(kind)) {
            searchTypes(engine, query, scope, symbols, monitor);
        }
        if (kind == null || "method".equals(kind)) {
            searchDeclarations(engine, query, IJavaSearchConstants.METHOD, "method", scope, symbols, monitor);
        }
        if (kind == null || "field".equals(kind)) {
            searchDeclarations(engine, query, IJavaSearchConstants.FIELD, "field", scope, symbols, monitor);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("query", query);
        result.put("count", symbols.size());
        result.put("symbols", symbols);
        return result;
    }

    private void searchTypes(SearchEngine engine, String query, IJavaSearchScope scope,
                             List<Map<String, Object>> symbols, IProgressMonitor monitor) throws Exception {
        engine.searchAllTypeNames(
                null,
                0,
                query.toCharArray(),
                SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CAMELCASE_MATCH,
                IJavaSearchConstants.TYPE,
                scope,
                new TypeNameMatchRequestor() {
                    @Override
                    public void acceptTypeNameMatch(TypeNameMatch match) {
                        Map<String, Object> symbol = new HashMap<>();
                        symbol.put("name", match.getSimpleTypeName());
                        symbol.put("fqn", match.getFullyQualifiedName());
                        symbol.put("kind", "type");
                        IType type = match.getType();
                        if (type.getResource() != null) {
                            symbol.put("uri", JdtUtils.toFileUri(type.getResource()));
                        }
                        symbols.add(symbol);
                    }
                },
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                monitor);
    }

    private void searchDeclarations(SearchEngine engine, String query, int searchFor, String kindLabel,
                                    IJavaSearchScope scope, List<Map<String, Object>> symbols,
                                    IProgressMonitor monitor) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
                query,
                searchFor,
                IJavaSearchConstants.DECLARATIONS,
                SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CAMELCASE_MATCH);
        if (pattern == null) {
            return;
        }

        engine.search(
                pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        Map<String, Object> symbol = new HashMap<>();
                        if (match.getElement() instanceof IJavaElement element) {
                            symbol.put("name", element.getElementName());
                            symbol.put("kind", kindLabel);
                            IType declaringType = (IType) element.getAncestor(IJavaElement.TYPE);
                            if (declaringType != null) {
                                symbol.put("fqn", declaringType.getFullyQualifiedName() + "." + element.getElementName());
                            }
                            if (match.getResource() != null) {
                                symbol.put("uri", JdtUtils.toFileUri(match.getResource()));
                            }
                            symbols.add(symbol);
                        }
                    }
                },
                monitor);
    }
}
