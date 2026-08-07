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
import org.eclipse.jdt.core.IMethod;
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
 * Handler for "mcp.jdtls.findMethodReferences" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the method at the given position via {@code codeSelect}, then
 * searches for all references to that method across the workspace.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindMethodReferencesTool.java">javalens-mcp FindMethodReferencesTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindMethodReferencesHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }
        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");
        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);
        IMethod method = null;
        for (IJavaElement el : elements) {
            if (el instanceof IMethod) {
                method = (IMethod) el;
                break;
            }
        }
        if (method == null) {
            throw new RuntimeException("No method found at position");
        }

        SearchPattern pattern = SearchPattern.createPattern(method, IJavaSearchConstants.REFERENCES);
        if (pattern == null) {
            return Map.of("method", method.getElementName(), "methodReferences", List.of());
        }

        IJavaSearchScope scope = JdtUtils.resolveSearchScope(arguments);
        List<Map<String, Object>> refs = new ArrayList<>();
        Map<IResource, String> sourceCache = new HashMap<>();

        SearchEngine engine = new SearchEngine();
        engine.search(
                pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        refs.add(JdtUtils.formatSearchMatch(match, sourceCache));
                    }
                },
                monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("method", method.getElementName());
        result.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
        result.put("count", refs.size());
        result.put("methodReferences", JdtUtils.groupResultsByUri(refs));
        return result;
    }
}
