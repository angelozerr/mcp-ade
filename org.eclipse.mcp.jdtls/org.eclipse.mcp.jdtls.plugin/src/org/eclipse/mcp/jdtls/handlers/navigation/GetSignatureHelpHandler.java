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
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Handler for "mcp.jdtls.getSignatureHelp" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the method at the given position and returns its full
 * signature information including parameters with Javadoc descriptions,
 * type parameters, and thrown exceptions.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetSignatureHelpTool.java">javalens-mcp GetSignatureHelpTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetSignatureHelpHandler extends AbstractPositionHandler {

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

        if (method.getDeclaringType() != null) {
            result.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
        }

        result.put("returnType", Signature.toString(method.getReturnType()));

        // Parameters with Javadoc descriptions
        String[] paramNames = method.getParameterNames();
        String[] paramTypes = method.getParameterTypes();
        Map<String, String> paramDescriptions = extractParamDescriptions(method, monitor);

        List<Map<String, String>> parameters = new ArrayList<>();
        for (int i = 0; i < paramNames.length; i++) {
            Map<String, String> param = new HashMap<>();
            param.put("name", paramNames[i]);
            param.put("type", Signature.toString(paramTypes[i]));
            String desc = paramDescriptions.get(paramNames[i]);
            if (desc != null) {
                param.put("javadocDescription", desc);
            }
            parameters.add(param);
        }
        result.put("parameters", parameters);

        // Type parameters
        ITypeParameter[] typeParams = method.getTypeParameters();
        if (typeParams.length > 0) {
            List<String> typeParamNames = new ArrayList<>();
            for (ITypeParameter tp : typeParams) {
                typeParamNames.add(tp.getElementName());
            }
            result.put("typeParameters", typeParamNames);
        }

        // Thrown exceptions
        String[] exceptionTypes = method.getExceptionTypes();
        List<String> exceptions = new ArrayList<>();
        for (String exType : exceptionTypes) {
            exceptions.add(Signature.toString(exType));
        }
        result.put("exceptions", exceptions);

        return result;
    }

    /**
     * Extract @param descriptions from the method's Javadoc.
     */
    private Map<String, String> extractParamDescriptions(IMethod method, IProgressMonitor monitor) {
        Map<String, String> descriptions = new HashMap<>();
        try {
            String javadocSource = extractJavadocSource(method);
            if (javadocSource == null) {
                return descriptions;
            }

            String[] lines = javadocSource.split("\\n");
            for (String line : lines) {
                String trimmed = line.trim();
                // Remove leading asterisk
                if (trimmed.startsWith("* ")) {
                    trimmed = trimmed.substring(2);
                } else if (trimmed.startsWith("*")) {
                    trimmed = trimmed.substring(1);
                }
                trimmed = trimmed.trim();

                if (trimmed.startsWith("@param ")) {
                    String rest = trimmed.substring(7).trim();
                    int spaceIdx = rest.indexOf(' ');
                    if (spaceIdx > 0) {
                        String paramName = rest.substring(0, spaceIdx);
                        String desc = rest.substring(spaceIdx + 1).trim();
                        descriptions.put(paramName, desc);
                    }
                }
            }
        } catch (JavaModelException e) {
            // Ignore
        }
        return descriptions;
    }

    private String extractJavadocSource(IMember member) throws JavaModelException {
        ISourceRange javadocRange = member.getJavadocRange();
        if (javadocRange == null) {
            return null;
        }

        ICompilationUnit memberCu = member.getCompilationUnit();
        if (memberCu == null) {
            return null;
        }

        String source = memberCu.getSource();
        if (source == null) {
            return null;
        }

        int start = javadocRange.getOffset();
        int end = start + javadocRange.getLength();
        if (end > source.length()) {
            end = source.length();
        }

        return source.substring(start, end);
    }
}
