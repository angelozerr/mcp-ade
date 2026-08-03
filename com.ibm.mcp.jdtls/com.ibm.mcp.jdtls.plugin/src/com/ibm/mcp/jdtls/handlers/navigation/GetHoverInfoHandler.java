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
package com.ibm.mcp.jdtls.handlers.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.core.manipulation.JavaElementLabelsCore;

/**
 * Handler for "mcp.jdtls.getHoverInfo" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the element at the given position and returns rich hover
 * information including name, kind, signature, Javadoc text, modifiers,
 * declaring type, and kind-specific details.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetHoverInfoTool.java">javalens-mcp GetHoverInfoTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetHoverInfoHandler extends AbstractPositionHandler {

    private static final long LABEL_FLAGS =
            JavaElementLabelsCore.ALL_FULLY_QUALIFIED
            | JavaElementLabelsCore.M_PRE_RETURNTYPE
            | JavaElementLabelsCore.M_PARAMETER_ANNOTATIONS
            | JavaElementLabelsCore.M_PARAMETER_TYPES
            | JavaElementLabelsCore.M_PARAMETER_NAMES
            | JavaElementLabelsCore.M_EXCEPTIONS
            | JavaElementLabelsCore.F_PRE_TYPE_SIGNATURE
            | JavaElementLabelsCore.M_PRE_TYPE_PARAMETERS
            | JavaElementLabelsCore.T_TYPE_PARAMETERS
            | JavaElementLabelsCore.USE_RESOLVED;

    private static final long LOCAL_VARIABLE_FLAGS =
            LABEL_FLAGS & ~JavaElementLabelsCore.F_FULLY_QUALIFIED
            | JavaElementLabelsCore.F_POST_QUALIFIED;

    private static final long COMMON_SIGNATURE_FLAGS =
            LABEL_FLAGS & ~JavaElementLabelsCore.ALL_FULLY_QUALIFIED
            | JavaElementLabelsCore.T_FULLY_QUALIFIED
            | JavaElementLabelsCore.M_FULLY_QUALIFIED;

    @Override
    protected Object handleElements(IJavaElement[] elements, ICompilationUnit cu, int offset,
            IProgressMonitor monitor) throws Exception {
        if (elements.length == 0) {
            return Map.of("error", "No element found at position");
        }

        IJavaElement element = elements[0];
        Map<String, Object> result = new HashMap<>();
        result.put("element", element.getElementName());
        result.put("kind", getElementKind(element));

        // Rich signature (same approach as JDT.LS HoverInfoProvider)
        if (element instanceof ILocalVariable) {
            result.put("signature", JavaElementLabelsCore.getElementLabel(element, LOCAL_VARIABLE_FLAGS));
        } else {
            result.put("signature", JavaElementLabelsCore.getElementLabel(element, COMMON_SIGNATURE_FLAGS));
        }

        if (element instanceof IMember member) {
            String javadoc = extractSourceJavadoc(member);
            if (javadoc != null) {
                result.put("javadoc", javadoc);
            }

            result.put("modifiers", getModifiersList(member.getFlags()));

            if (member.getDeclaringType() != null) {
                result.put("declaringType", member.getDeclaringType().getFullyQualifiedName());
            }
        }

        if (element instanceof IMethod method) {
            addMethodInfo(method, result);
        } else if (element instanceof IField field) {
            addFieldInfo(field, result);
        } else if (element instanceof IType type) {
            addTypeInfo(type, result);
        } else if (element instanceof ILocalVariable variable) {
            result.put("kind", "variable");
            result.put("type", Signature.toString(variable.getTypeSignature()));
            result.put("isParameter", variable.isParameter());
        }

        return result;
    }

    private void addMethodInfo(IMethod method, Map<String, Object> result) throws JavaModelException {
        result.put("returnType", Signature.toString(method.getReturnType()));

        String[] paramNames = method.getParameterNames();
        String[] paramTypes = method.getParameterTypes();
        List<Map<String, String>> params = new ArrayList<>();
        for (int i = 0; i < paramNames.length; i++) {
            Map<String, String> param = new HashMap<>();
            param.put("name", paramNames[i]);
            param.put("type", Signature.toString(paramTypes[i]));
            params.add(param);
        }
        result.put("parameters", params);
    }

    private void addFieldInfo(IField field, Map<String, Object> result) throws JavaModelException {
        result.put("type", Signature.toString(field.getTypeSignature()));

        Object constant = field.getConstant();
        if (constant != null) {
            result.put("initialValue", constant.toString());
        }
    }

    private void addTypeInfo(IType type, Map<String, Object> result) throws JavaModelException {
        result.put("typeKind", getTypeKind(type));

        String superclass = type.getSuperclassName();
        if (superclass != null) {
            result.put("superclass", superclass);
        }

        String[] interfaces = type.getSuperInterfaceNames();
        if (interfaces.length > 0) {
            result.put("interfaces", List.of(interfaces));
        }
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
            default:
                return "unknown";
        }
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

    private String extractSourceJavadoc(IMember member) {
        try {
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
            int end = Math.min(start + javadocRange.getLength(), source.length());
            return source.substring(start, end);
        } catch (JavaModelException e) {
            return null;
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
        if (Flags.isSynchronized(flags)) modifiers.add("synchronized");
        if (Flags.isNative(flags)) modifiers.add("native");
        if (Flags.isDefaultMethod(flags)) modifiers.add("default");
        return modifiers;
    }
}
