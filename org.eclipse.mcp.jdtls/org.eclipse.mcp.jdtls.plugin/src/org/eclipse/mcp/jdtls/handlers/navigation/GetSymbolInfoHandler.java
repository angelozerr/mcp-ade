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
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Handler for "mcp.jdtls.getSymbolInfo" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the element at the given position and returns detailed
 * information depending on the element kind (field, method, type, or
 * local variable).</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetSymbolInfoTool.java">javalens-mcp GetSymbolInfoTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetSymbolInfoHandler extends AbstractPositionHandler {

    @Override
    protected Object handleElements(IJavaElement[] elements, ICompilationUnit cu, int offset,
            IProgressMonitor monitor) throws Exception {
        if (elements.length == 0) {
            throw new RuntimeException("No element found at position");
        }

        IJavaElement element = elements[0];
        Map<String, Object> result = new HashMap<>();
        result.put("element", element.getElementName());

        if (element instanceof IField field) {
            result.put("kind", "field");
            addFieldInfo(field, result);
        } else if (element instanceof IMethod method) {
            result.put("kind", "method");
            addMethodInfo(method, result);
        } else if (element instanceof IType type) {
            result.put("kind", "type");
            addTypeInfo(type, result);
        } else if (element instanceof ILocalVariable variable) {
            result.put("kind", "variable");
            addLocalVariableInfo(variable, result);
        } else {
            result.put("kind", getElementKind(element));
        }

        return result;
    }

    private void addFieldInfo(IField field, Map<String, Object> result) throws JavaModelException {
        result.put("name", field.getElementName());
        result.put("type", Signature.toString(field.getTypeSignature()));
        result.put("modifiers", getModifiersList(field.getFlags()));

        if (field.getDeclaringType() != null) {
            result.put("declaringType", field.getDeclaringType().getFullyQualifiedName());
        }

        result.put("isEnumConstant", field.isEnumConstant());

        Object constant = field.getConstant();
        if (constant != null) {
            result.put("initialValue", constant.toString());
        }
    }

    private void addMethodInfo(IMethod method, Map<String, Object> result) throws JavaModelException {
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
        result.put("thrownExceptions", exceptions);
    }

    private void addTypeInfo(IType type, Map<String, Object> result) throws JavaModelException {
        result.put("name", type.getElementName());
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

        ITypeParameter[] typeParams = type.getTypeParameters();
        if (typeParams.length > 0) {
            List<String> typeParamNames = new ArrayList<>();
            for (ITypeParameter tp : typeParams) {
                typeParamNames.add(tp.getElementName());
            }
            result.put("typeParameters", typeParamNames);
        }
    }

    private void addLocalVariableInfo(ILocalVariable variable, Map<String, Object> result) {
        result.put("name", variable.getElementName());
        result.put("type", Signature.toString(variable.getTypeSignature()));
        result.put("isParameter", variable.isParameter());
    }

    private String getElementKind(IJavaElement element) {
        switch (element.getElementType()) {
            case IJavaElement.PACKAGE_DECLARATION:
                return "package";
            case IJavaElement.IMPORT_DECLARATION:
                return "import";
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
        if (Flags.isTransient(flags)) modifiers.add("transient");
        if (Flags.isVolatile(flags)) modifiers.add("volatile");
        return modifiers;
    }
}
