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
package org.eclipse.mcp.jdtls.handlers.navigation;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getSuperMethod" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves an {@link IMethod} at the given position. Walks up the type
 * hierarchy (superclass chain and interface chain) to find the method that
 * this method overrides or implements.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetSuperMethodTool.java">javalens-mcp GetSuperMethodTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetSuperMethodHandler extends AbstractPositionHandler {

    @Override
    protected Object handleElements(IJavaElement[] elements, ICompilationUnit cu, int offset,
            IProgressMonitor monitor) throws Exception {
        if (elements.length == 0) {
            throw new RuntimeException("No element found at position");
        }

        IMethod method = null;
        for (IJavaElement element : elements) {
            if (element instanceof IMethod) {
                method = (IMethod) element;
                break;
            }
        }

        if (method == null) {
            throw new RuntimeException("No method found at position");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("method", method.getElementName());
        result.put("declaringType", method.getDeclaringType().getFullyQualifiedName());

        IMethod superMethod = findSuperMethod(method, monitor);
        if (superMethod != null) {
            Map<String, Object> superInfo = new HashMap<>();
            superInfo.put("name", superMethod.getElementName());
            superInfo.put("declaringType", superMethod.getDeclaringType().getFullyQualifiedName());

            if (superMethod.getResource() != null) {
                superInfo.put("uri", JdtUtils.toFileUri(superMethod.getResource()));
            } else {
                ICompilationUnit defCu = superMethod.getCompilationUnit();
                if (defCu != null && defCu.getResource() != null) {
                    superInfo.put("uri", JdtUtils.toFileUri(defCu.getResource()));
                }
            }

            superInfo.put("line", getElementLine(superMethod));
            result.put("superMethod", superInfo);
        } else {
            result.put("superMethod", null);
        }

        return result;
    }

    /**
     * Walk the type hierarchy to find a method with the same signature in a
     * superclass or implemented interface.
     */
    private IMethod findSuperMethod(IMethod method, IProgressMonitor monitor) throws JavaModelException {
        IType declaringType = method.getDeclaringType();
        if (declaringType == null) {
            return null;
        }

        String methodName = method.getElementName();
        String[] paramTypes = method.getParameterTypes();

        ITypeHierarchy hierarchy = declaringType.newTypeHierarchy(monitor);

        // Check superclass chain first
        IType[] superclasses = hierarchy.getAllSuperclasses(declaringType);
        for (IType superType : superclasses) {
            IMethod found = findMatchingMethod(superType, methodName, paramTypes);
            if (found != null) {
                return found;
            }
        }

        // Check interface chain
        IType[] superInterfaces = hierarchy.getAllSuperInterfaces(declaringType);
        for (IType superInterface : superInterfaces) {
            IMethod found = findMatchingMethod(superInterface, methodName, paramTypes);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    /**
     * Find a method in the given type that matches the specified name and
     * parameter types.
     */
    private IMethod findMatchingMethod(IType type, String methodName, String[] paramTypes) throws JavaModelException {
        IMethod[] methods = type.getMethods();
        for (IMethod candidate : methods) {
            if (!candidate.getElementName().equals(methodName)) {
                continue;
            }
            String[] candidateParams = candidate.getParameterTypes();
            if (candidateParams.length != paramTypes.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!paramTypes[i].equals(candidateParams[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return candidate;
            }
        }
        return null;
    }

    private int getElementLine(IMethod method) {
        try {
            if (method instanceof ISourceReference sourceRef) {
                ISourceRange range = sourceRef.getSourceRange();
                if (range != null) {
                    ICompilationUnit defCu = method.getCompilationUnit();
                    if (defCu != null) {
                        String source = defCu.getSource();
                        if (source != null) {
                            return offsetToLine(source, range.getOffset());
                        }
                    }
                }
            }
        } catch (JavaModelException e) {
            // Ignore
        }
        return -1;
    }

    private int offsetToLine(String source, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
