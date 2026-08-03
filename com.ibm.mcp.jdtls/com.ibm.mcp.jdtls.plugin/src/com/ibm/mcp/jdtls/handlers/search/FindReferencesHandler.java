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

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.findReferences" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the element at the given position via {@code codeSelect}, then
 * searches for all references to that element across the workspace.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindReferencesTool.java">javalens-mcp FindReferencesTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindReferencesHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of("error", "Missing arguments");
        }
        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");
        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        int maxResults = 0;
        Object mr = params.get("maxResults");
        if (mr instanceof Number n) {
            maxResults = n.intValue();
        }
        final int limit = maxResults;

        ICompilationUnit cu = JdtUtils.getCompilationUnit(uri);
        if (cu == null) {
            return Map.of("error", "Compilation unit not found");
        }

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);
        if (elements.length == 0) {
            return Map.of("error", "No element found at position");
        }

        IJavaElement target = elements[0];
        SearchPattern pattern = SearchPattern.createPattern(target, IJavaSearchConstants.REFERENCES);
        if (pattern == null) {
            return Map.of("element", target.getElementName(), "references", List.of());
        }

        IJavaSearchScope scope = JdtUtils.resolveSearchScope(arguments);
        List<Map<String, Object>> references = new ArrayList<>();
        Map<IResource, String> sourceCache = new HashMap<>();
        final boolean[] truncated = {false};

        SearchEngine engine = new SearchEngine();
        engine.search(
                pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (limit > 0 && references.size() >= limit) {
                            truncated[0] = true;
                            return;
                        }
                        references.add(JdtUtils.formatSearchMatch(match, sourceCache));
                    }
                },
                monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("element", target.getElementName());
        IType declaringType = (IType) target.getAncestor(IJavaElement.TYPE);
        if (declaringType != null) {
            result.put("declaringType", declaringType.getFullyQualifiedName());
        }
        result.put("count", references.size());
        if (truncated[0]) {
            result.put("truncated", true);
        }
        result.put("references", JdtUtils.groupResultsByUri(references));
        return result;
    }
}
