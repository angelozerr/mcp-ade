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

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
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
 * Handler for "mcp.jdtls.findImplementations" command.
 *
 * <p>Arguments: [{fullyQualifiedName}] or [{uri, line, character}]</p>
 *
 * <p>When the resolved element is an {@link IType}, uses
 * {@link ITypeHierarchy#getAllSubtypes} (same approach as JDT.LS).</p>
 *
 * <p>When the resolved element is an {@link IMethod}, uses
 * {@link SearchEngine} with a hierarchy scope to find concrete
 * method implementations (same approach as JDT.LS
 * ImplementationCollector).</p>
 */
public class FindImplementationsHandler implements ICommandHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        IJavaElement element = JdtUtils.resolveElement(arguments, monitor);

        if (element instanceof IType type) {
            if (!type.exists()) {
                return Map.of("error", "Type not found");
            }
            return findTypeImplementations(type, monitor);
        }

        if (element instanceof IMethod method) {
            return findMethodImplementations(method, monitor);
        }

        return Map.of("error", "Type or method not found");
    }

    private Object findTypeImplementations(IType type, IProgressMonitor monitor) throws JavaModelException {
        ITypeHierarchy hierarchy = type.newTypeHierarchy(monitor);
        IType[] subtypes = hierarchy.getAllSubtypes(type);

        List<Map<String, Object>> implementations = new ArrayList<>();
        for (IType subtype : subtypes) {
            if (monitor.isCanceled()) {
                break;
            }
            Map<String, Object> impl = new HashMap<>();
            impl.put("name", subtype.getElementName());
            impl.put("fqn", subtype.getFullyQualifiedName());
            impl.put("isInterface", Flags.isInterface(subtype.getFlags()));
            if (subtype.getResource() != null) {
                impl.put("uri", JdtUtils.toFileUri(subtype.getResource()));
            }
            implementations.add(impl);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", type.getFullyQualifiedName());
        result.put("count", implementations.size());
        result.put("implementations", implementations);
        return result;
    }

    private Object findMethodImplementations(IMethod method, IProgressMonitor monitor) throws Exception {
        if (cannotBeOverridden(method)) {
            return Map.of("error",
                    "Method cannot be overridden (private, final, static, or constructor)");
        }

        IType declaringType = method.getDeclaringType();

        IJavaSearchScope hierarchyScope;
        if (declaringType.isInterface()) {
            hierarchyScope = SearchEngine.createHierarchyScope(declaringType);
        } else {
            boolean isMethodAbstract = Flags.isAbstract(method.getFlags());
            hierarchyScope = SearchEngine.createStrictHierarchyScope(
                    null, declaringType, true, isMethodAbstract, null);
        }

        int limitTo = IJavaSearchConstants.DECLARATIONS
                | IJavaSearchConstants.IGNORE_DECLARING_TYPE
                | IJavaSearchConstants.IGNORE_RETURN_TYPE;
        SearchPattern pattern = SearchPattern.createPattern(method, limitTo);
        if (pattern == null) {
            return Map.of("error", "Cannot create search pattern for method");
        }

        List<Map<String, Object>> implementations = new ArrayList<>();
        SearchEngine engine = new SearchEngine();
        engine.search(pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                hierarchyScope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) throws CoreException {
                        if (match.getAccuracy() != SearchMatch.A_ACCURATE) {
                            return;
                        }
                        if (!(match.getElement() instanceof IMethod found)) {
                            return;
                        }
                        try {
                            if (Flags.isAbstract(found.getFlags())) {
                                return;
                            }
                        } catch (JavaModelException e) {
                            return;
                        }
                        Map<String, Object> impl = new HashMap<>();
                        impl.put("name", found.getElementName());
                        impl.put("declaringType", found.getDeclaringType().getFullyQualifiedName());
                        impl.put("fqn", found.getDeclaringType().getFullyQualifiedName()
                                + "#" + found.getElementName());
                        if (found.getResource() != null) {
                            impl.put("uri", JdtUtils.toFileUri(found.getResource()));
                        }
                        implementations.add(impl);
                    }
                },
                monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("method", declaringType.getFullyQualifiedName() + "#" + method.getElementName());
        result.put("count", implementations.size());
        result.put("implementations", implementations);
        return result;
    }

    private static boolean cannotBeOverridden(IMethod method) throws JavaModelException {
        int flags = method.getFlags();
        if (Flags.isPrivate(flags) || Flags.isFinal(flags) || Flags.isStatic(flags)) {
            return true;
        }
        if (method.isConstructor()) {
            return true;
        }
        return Flags.isFinal(method.getDeclaringType().getFlags());
    }
}
