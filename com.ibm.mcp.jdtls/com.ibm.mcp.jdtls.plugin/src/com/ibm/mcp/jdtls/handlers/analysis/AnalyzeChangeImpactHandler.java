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
package com.ibm.mcp.jdtls.handlers.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
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
 * Handler for "mcp.jdtls.analyzeChangeImpact" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the element at the given position and builds an impact chain:
 * element -> direct callers -> their callers (up to 2 levels).</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/AnalyzeChangeImpactTool.java">javalens-mcp AnalyzeChangeImpactTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class AnalyzeChangeImpactHandler implements ICommandHandler {

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

        int maxResults = 0;
        Object mr = params.get("maxResults");
        if (mr instanceof Number n) {
            maxResults = n.intValue();
        }
        final int limit = maxResults > 0 ? maxResults : 50;

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);
        if (elements.length == 0) {
            throw new RuntimeException("No element found at position");
        }

        IJavaElement element = elements[0];
        boolean truncated = false;

        // Find direct references (level 1)
        List<IMember> directCallers = findReferences(element, monitor);
        if (directCallers.size() > limit) {
            directCallers = directCallers.subList(0, limit);
            truncated = true;
        }
        List<Map<String, Object>> directImpact = new ArrayList<>();
        for (IMember caller : directCallers) {
            directImpact.add(formatMember(caller));
        }

        // Find transitive references (level 2) — skip if direct already hit the limit
        List<Map<String, Object>> transitiveImpact = new ArrayList<>();
        if (!truncated) {
            Set<String> seen = new HashSet<>();
            for (IMember caller : directCallers) {
                seen.add(caller.getHandleIdentifier());
            }
            for (IMember caller : directCallers) {
                if (monitor != null && monitor.isCanceled()) {
                    break;
                }
                if (transitiveImpact.size() >= limit) {
                    truncated = true;
                    break;
                }
                List<IMember> transCallers = findReferences(caller, monitor);
                for (IMember transCaller : transCallers) {
                    if (transitiveImpact.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    if (seen.add(transCaller.getHandleIdentifier())) {
                        transitiveImpact.add(formatMember(transCaller));
                    }
                }
            }
        }

        // Count unique affected files
        Set<String> affectedFiles = new HashSet<>();
        for (IMember caller : directCallers) {
            if (caller.getResource() != null) {
                affectedFiles.add(JdtUtils.toFileUri(caller.getResource()));
            }
        }
        for (Map<String, Object> trans : transitiveImpact) {
            Object transUri = trans.get("uri");
            if (transUri != null) {
                affectedFiles.add(transUri.toString());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("element", formatElement(element));
        result.put("directImpact", directImpact);
        result.put("transitiveImpact", transitiveImpact);
        result.put("affectedFiles", affectedFiles.size());
        if (truncated) {
            result.put("truncated", true);
        }
        return result;
    }

    private List<IMember> findReferences(IJavaElement element, IProgressMonitor monitor) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
                element,
                IJavaSearchConstants.REFERENCES);

        if (pattern == null) {
            return List.of();
        }

        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        List<IMember> callers = new ArrayList<>();

        SearchEngine engine = new SearchEngine();
        engine.search(
                pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        Object matchElement = match.getElement();
                        if (matchElement instanceof IMember member) {
                            callers.add(member);
                        }
                    }
                },
                monitor);

        return callers;
    }

    private Map<String, Object> formatElement(IJavaElement element) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", element.getElementName());
        info.put("kind", element.getElementType());
        if (element instanceof IMember member && member.getDeclaringType() != null) {
            info.put("declaringType", member.getDeclaringType().getFullyQualifiedName());
        }
        if (element.getResource() != null) {
            info.put("uri", JdtUtils.toFileUri(element.getResource()));
        }
        return info;
    }

    private Map<String, Object> formatMember(IMember member) {
        Map<String, Object> info = new HashMap<>();
        info.put("name", member.getElementName());
        info.put("kind", member.getElementType());
        if (member.getDeclaringType() != null) {
            info.put("declaringType", member.getDeclaringType().getFullyQualifiedName());
        }
        if (member.getResource() != null) {
            info.put("uri", JdtUtils.toFileUri(member.getResource()));
        }
        return info;
    }
}
