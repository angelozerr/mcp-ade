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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getTypeAtPosition" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the element at the given position. If it is an {@link IType},
 * or can provide an enclosing {@link IType}, returns detailed type
 * information.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetTypeAtPositionTool.java">javalens-mcp GetTypeAtPositionTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetTypeAtPositionHandler extends AbstractPositionHandler {

    @Override
    protected Object handleElements(IJavaElement[] elements, ICompilationUnit cu, int offset,
            IProgressMonitor monitor) throws Exception {
        if (elements.length == 0) {
            throw new RuntimeException("No element found at position");
        }

        IType type = null;
        for (IJavaElement element : elements) {
            if (element instanceof IType) {
                type = (IType) element;
                break;
            }
        }

        // If no direct IType found, get enclosing type
        if (type == null) {
            type = (IType) elements[0].getAncestor(IJavaElement.TYPE);
        }

        if (type == null) {
            throw new RuntimeException("No type found at position");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("name", type.getElementName());
        result.put("fullyQualifiedName", type.getFullyQualifiedName());
        result.put("kind", getTypeKind(type));
        result.put("modifiers", getModifiersList(type.getFlags()));

        String superclass = type.getSuperclassName();
        if (superclass != null) {
            result.put("superclass", superclass);
        }

        String[] interfaces = type.getSuperInterfaceNames();
        List<String> interfaceList = new ArrayList<>();
        for (String iface : interfaces) {
            interfaceList.add(iface);
        }
        result.put("superInterfaces", interfaceList);

        // URI of the defining file
        if (type.getResource() != null) {
            result.put("uri", JdtUtils.toFileUri(type.getResource()));
        } else {
            ICompilationUnit defCu = type.getCompilationUnit();
            if (defCu != null && defCu.getResource() != null) {
                result.put("uri", JdtUtils.toFileUri(defCu.getResource()));
            }
        }

        // Line number
        result.put("line", getElementLine(type));

        return result;
    }

    private int getElementLine(IType type) {
        try {
            if (type instanceof ISourceReference sourceRef) {
                ISourceRange range = sourceRef.getSourceRange();
                if (range != null) {
                    ICompilationUnit defCu = type.getCompilationUnit();
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

    private String getTypeKind(IType type) throws JavaModelException {
        if (type.isAnnotation()) {
            return "annotation";
        } else if (type.isEnum()) {
            return "enum";
        } else if (type.isRecord()) {
            return "record";
        } else if (type.isInterface()) {
            return "interface";
        } else {
            return "class";
        }
    }

    private List<String> getModifiersList(int flags) {
        List<String> modifiers = new ArrayList<>();
        if (Flags.isPublic(flags)) modifiers.add("public");
        if (Flags.isPrivate(flags)) modifiers.add("private");
        if (Flags.isProtected(flags)) modifiers.add("protected");
        if (Flags.isStatic(flags)) modifiers.add("static");
        if (Flags.isFinal(flags)) modifiers.add("final");
        if (Flags.isAbstract(flags)) modifiers.add("abstract");
        return modifiers;
    }
}
