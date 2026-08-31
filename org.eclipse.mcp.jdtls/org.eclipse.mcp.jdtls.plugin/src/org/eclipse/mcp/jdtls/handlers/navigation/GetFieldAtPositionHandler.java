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
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getFieldAtPosition" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the element at the given position. If it is an {@link IField},
 * returns detailed field information. Otherwise returns an error.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetFieldAtPositionTool.java">javalens-mcp GetFieldAtPositionTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetFieldAtPositionHandler extends AbstractPositionHandler {

    @Override
    protected Object handleElements(IJavaElement[] elements, ICompilationUnit cu, int offset,
            IProgressMonitor monitor) throws Exception {
        if (elements.length == 0) {
            throw new RuntimeException("No element found at position");
        }

        IField field = null;
        for (IJavaElement element : elements) {
            if (element instanceof IField) {
                field = (IField) element;
                break;
            }
        }

        if (field == null) {
            throw new RuntimeException("Element at position is not a field");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("name", field.getElementName());
        result.put("type", Signature.toString(field.getTypeSignature()));
        result.put("modifiers", getModifiersList(field.getFlags()));

        if (field.getDeclaringType() != null) {
            result.put("declaringType", field.getDeclaringType().getFullyQualifiedName());
        }

        result.put("isEnumConstant", field.isEnumConstant());

        // URI of the defining file
        if (field.getResource() != null) {
            result.put("uri", JdtUtils.toFileUri(field.getResource()));
        } else {
            ICompilationUnit defCu = field.getCompilationUnit();
            if (defCu != null && defCu.getResource() != null) {
                result.put("uri", JdtUtils.toFileUri(defCu.getResource()));
            }
        }

        // Line number
        result.put("line", getElementLine(field, cu));

        return result;
    }

    private int getElementLine(IField field, ICompilationUnit cu) {
        try {
            if (field instanceof ISourceReference sourceRef) {
                ISourceRange range = sourceRef.getSourceRange();
                if (range != null) {
                    ICompilationUnit defCu = field.getCompilationUnit();
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
        if (Flags.isTransient(flags)) modifiers.add("transient");
        if (Flags.isVolatile(flags)) modifiers.add("volatile");
        return modifiers;
    }
}
