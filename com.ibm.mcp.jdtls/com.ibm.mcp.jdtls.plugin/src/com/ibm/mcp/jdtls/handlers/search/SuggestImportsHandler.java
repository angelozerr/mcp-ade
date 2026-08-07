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
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

import com.ibm.mcp.jdtls.ICommandHandler;

/**
 * Handler for "mcp.jdtls.suggestImports" command.
 *
 * <p>Arguments: [{uri, typeName}]</p>
 *
 * <p>Searches for all types matching the given simple type name using
 * {@link SearchEngine#searchAllTypeNames} and returns fully qualified
 * name suggestions.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/SuggestImportsTool.java">javalens-mcp SuggestImportsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class SuggestImportsHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }
        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String typeName = (String) params.get("typeName");
        if (typeName == null || typeName.isBlank()) {
            throw new RuntimeException("Missing typeName parameter");
        }

        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        List<String> suggestions = new ArrayList<>();

        SearchEngine engine = new SearchEngine();
        engine.searchAllTypeNames(
                null,
                0,
                typeName.toCharArray(),
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                IJavaSearchConstants.TYPE,
                scope,
                new TypeNameMatchRequestor() {
                    @Override
                    public void acceptTypeNameMatch(TypeNameMatch match) {
                        suggestions.add(match.getFullyQualifiedName());
                    }
                },
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("typeName", typeName);
        result.put("count", suggestions.size());
        result.put("suggestions", suggestions);
        return result;
    }
}
