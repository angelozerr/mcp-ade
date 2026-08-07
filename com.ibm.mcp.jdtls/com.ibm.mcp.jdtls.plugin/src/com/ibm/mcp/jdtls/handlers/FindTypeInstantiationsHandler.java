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
package com.ibm.mcp.jdtls.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
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
 * Handler for "mcp.jdtls.findTypeInstantiations" command.
 *
 * <p>Arguments: [{fullyQualifiedName}] or [{uri, line, character}]</p>
 *
 * <p>Finds all {@code new Type()} instantiations using fine-grained
 * CLASS_INSTANCE_CREATION_TYPE_REFERENCE search.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindTypeInstantiationsTool.java">javalens-mcp FindTypeInstantiationsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindTypeInstantiationsHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        IType type = JdtUtils.resolveType(arguments, monitor);
        if (type == null || !type.exists()) {
            throw new RuntimeException("Type not found");
        }

        int maxResults = 0;
        if (arguments != null && !arguments.isEmpty() && arguments.get(0) instanceof Map) {
            Object mr = ((Map<String, Object>) arguments.get(0)).get("maxResults");
            if (mr instanceof Number n) {
                maxResults = n.intValue();
            }
        }
        final int limit = maxResults;

        SearchPattern pattern = SearchPattern.createPattern(
                type,
                IJavaSearchConstants.CLASS_INSTANCE_CREATION_TYPE_REFERENCE);

        if (pattern == null) {
            return Map.of("type", type.getFullyQualifiedName(), "instantiations", List.of());
        }

        IJavaSearchScope scope = JdtUtils.resolveSearchScope(arguments);
        List<Map<String, Object>> instantiations = new ArrayList<>();
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
                        if (limit > 0 && instantiations.size() >= limit) {
                            truncated[0] = true;
                            return;
                        }
                        instantiations.add(JdtUtils.formatSearchMatch(match, sourceCache));
                    }
                },
                monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("type", type.getFullyQualifiedName());
        result.put("count", instantiations.size());
        if (truncated[0]) {
            result.put("truncated", true);
        }
        result.put("instantiations", JdtUtils.groupResultsByUri(instantiations));
        return result;
    }
}
