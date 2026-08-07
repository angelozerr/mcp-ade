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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

import java.util.List;

/**
 * Handler for "mcp.jdtls.getTypeUsageSummary" command.
 *
 * <p>Arguments: [{fullyQualifiedName}] or [{uri, line, character}]</p>
 *
 * <p>Resolves the type and runs multiple fine-grained searches to build a
 * comprehensive usage summary including total references, instantiations,
 * casts, instanceof checks, annotation usages, and type argument usages.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetTypeUsageSummaryTool.java">javalens-mcp GetTypeUsageSummaryTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetTypeUsageSummaryHandler implements ICommandHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        IType type = JdtUtils.resolveType(arguments, monitor);
        if (type == null || !type.exists()) {
            throw new RuntimeException("Type not found");
        }

        String fqn = type.getFullyQualifiedName();
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        SearchEngine engine = new SearchEngine();

        int totalReferences = countMatches(engine, type, IJavaSearchConstants.REFERENCES, scope, monitor);
        int instantiations = countMatches(engine, type, IJavaSearchConstants.CLASS_INSTANCE_CREATION_TYPE_REFERENCE, scope, monitor);
        int casts = countMatches(engine, type, IJavaSearchConstants.CAST_TYPE_REFERENCE, scope, monitor);
        int instanceofs = countMatches(engine, type, IJavaSearchConstants.INSTANCEOF_TYPE_REFERENCE, scope, monitor);
        int annotations = countMatches(engine, type, IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE, scope, monitor);
        int typeArguments = countMatches(engine, type, IJavaSearchConstants.TYPE_ARGUMENT_TYPE_REFERENCE, scope, monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("type", fqn);
        result.put("totalReferences", totalReferences);
        result.put("instantiations", instantiations);
        result.put("casts", casts);
        result.put("instanceofs", instanceofs);
        result.put("annotations", annotations);
        result.put("typeArguments", typeArguments);
        return result;
    }

    private int countMatches(SearchEngine engine, IType type, int searchConstant,
                             IJavaSearchScope scope, IProgressMonitor monitor) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(type, searchConstant);
        if (pattern == null) {
            return 0;
        }

        AtomicInteger count = new AtomicInteger(0);
        engine.search(
                pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        count.incrementAndGet();
                    }
                },
                monitor);
        return count.get();
    }
}
