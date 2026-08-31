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
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getMethodAtPosition" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the element at the given position. If it is an {@link IMethod},
 * returns detailed method information. Otherwise returns an error.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetMethodAtPositionTool.java">javalens-mcp GetMethodAtPositionTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetMethodAtPositionHandler extends AbstractPositionHandler {

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
            throw new RuntimeException("Element at position is not a method");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("name", method.getElementName());
        result.put("returnType", Signature.toString(method.getReturnType()));

        String[] paramNames = method.getParameterNames();
        String[] paramTypes = method.getParameterTypes();
        List<String> paramNameList = new ArrayList<>();
        List<String> paramTypeList = new ArrayList<>();
        for (int i = 0; i < paramNames.length; i++) {
            paramNameList.add(paramNames[i]);
            paramTypeList.add(Signature.toString(paramTypes[i]));
        }
        result.put("parameterNames", paramNameList);
        result.put("parameterTypes", paramTypeList);

        result.put("modifiers", getModifiersList(method.getFlags()));

        if (method.getDeclaringType() != null) {
            result.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
        }

        result.put("isConstructor", method.isConstructor());

        String[] exceptionTypes = method.getExceptionTypes();
        List<String> exceptions = new ArrayList<>();
        for (String exType : exceptionTypes) {
            exceptions.add(Signature.toString(exType));
        }
        result.put("exceptions", exceptions);

        // URI of the defining file
        if (method.getResource() != null) {
            result.put("uri", JdtUtils.toFileUri(method.getResource()));
        } else {
            ICompilationUnit defCu = method.getCompilationUnit();
            if (defCu != null && defCu.getResource() != null) {
                result.put("uri", JdtUtils.toFileUri(defCu.getResource()));
            }
        }

        // Line number
        result.put("line", getElementLine(method));

        return result;
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

    private List<String> getModifiersList(int flags) {
        List<String> modifiers = new ArrayList<>();
        if (Flags.isPublic(flags)) modifiers.add("public");
        if (Flags.isPrivate(flags)) modifiers.add("private");
        if (Flags.isProtected(flags)) modifiers.add("protected");
        if (Flags.isStatic(flags)) modifiers.add("static");
        if (Flags.isFinal(flags)) modifiers.add("final");
        if (Flags.isAbstract(flags)) modifiers.add("abstract");
        if (Flags.isSynchronized(flags)) modifiers.add("synchronized");
        if (Flags.isNative(flags)) modifiers.add("native");
        if (Flags.isDefaultMethod(flags)) modifiers.add("default");
        return modifiers;
    }
}
