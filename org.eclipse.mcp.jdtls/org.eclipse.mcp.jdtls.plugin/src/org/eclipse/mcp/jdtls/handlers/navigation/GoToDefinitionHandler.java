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
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.goToDefinition" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Uses {@code codeSelect} to find the element at the given position and returns
 * the source location where that element is defined.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GoToDefinitionTool.java">javalens-mcp GoToDefinitionTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GoToDefinitionHandler extends AbstractPositionHandler {

    @Override
    protected Object handleElements(IJavaElement[] elements, ICompilationUnit cu, int offset,
            IProgressMonitor monitor) throws Exception {
        if (elements.length == 0) {
            throw new RuntimeException("No element found at position");
        }

        IJavaElement element = elements[0];
        Map<String, Object> result = new HashMap<>();
        result.put("element", element.getElementName());
        result.put("kind", getElementKind(element));

        if (element instanceof ISourceReference sourceRef) {
            ISourceRange range = sourceRef.getSourceRange();
            if (range != null) {
                // Compute the URI of the defining resource
                if (element.getResource() != null) {
                    result.put("uri", JdtUtils.toFileUri(element.getResource()));
                } else if (element.getAncestor(IJavaElement.COMPILATION_UNIT) instanceof ICompilationUnit defCu) {
                    if (defCu.getResource() != null) {
                        result.put("uri", JdtUtils.toFileUri(defCu.getResource()));
                    }
                }

                // Compute line/character from the source range offset
                ICompilationUnit defCu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
                if (defCu != null) {
                    String source = defCu.getSource();
                    if (source != null) {
                        int[] lineChar = offsetToLineCharacter(source, range.getOffset());
                        result.put("line", lineChar[0]);
                        result.put("character", lineChar[1]);
                    }
                }
            }
        }

        return result;
    }

    private String getElementKind(IJavaElement element) {
        switch (element.getElementType()) {
            case IJavaElement.TYPE:
                return "type";
            case IJavaElement.METHOD:
                return "method";
            case IJavaElement.FIELD:
                return "field";
            case IJavaElement.LOCAL_VARIABLE:
                return "variable";
            case IJavaElement.PACKAGE_DECLARATION:
                return "package";
            case IJavaElement.IMPORT_DECLARATION:
                return "import";
            default:
                return "unknown";
        }
    }

    private int[] offsetToLineCharacter(String source, int offset) {
        int line = 0;
        int character = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return new int[]{line, character};
    }
}
