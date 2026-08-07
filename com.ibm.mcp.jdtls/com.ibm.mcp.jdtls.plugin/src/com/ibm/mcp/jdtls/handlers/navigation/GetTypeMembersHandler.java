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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getTypeMembers" command.
 *
 * <p>Arguments: [{fullyQualifiedName}] or [{uri, line, character}],
 * optionally with {includeInherited: boolean}.</p>
 *
 * <p>Resolves the {@link IType} and returns its methods, fields, and nested
 * types. When {@code includeInherited} is {@code true}, members from
 * supertypes are included as well.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetTypeMembersTool.java">javalens-mcp GetTypeMembersTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetTypeMembersHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        boolean includeInherited = Boolean.TRUE.equals(params.get("includeInherited"));

        IType type = JdtUtils.resolveType(arguments, monitor);
        if (type == null || !type.exists()) {
            throw new RuntimeException("Type not found");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", type.getFullyQualifiedName());

        List<Map<String, Object>> methods = new ArrayList<>();
        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> nestedTypes = new ArrayList<>();

        // Add direct members
        addMembers(type, methods, fields, nestedTypes);

        // Add inherited members if requested
        if (includeInherited) {
            Set<String> seen = new HashSet<>();
            // Track direct member signatures to avoid duplicates
            for (Map<String, Object> m : methods) {
                seen.add((String) m.get("name") + "|" + m.get("parameterTypes"));
            }
            for (Map<String, Object> f : fields) {
                seen.add("field|" + f.get("name"));
            }

            ITypeHierarchy hierarchy = type.newTypeHierarchy(monitor);
            IType[] supertypes = hierarchy.getAllSuperclasses(type);
            for (IType superType : supertypes) {
                addInheritedMembers(superType, methods, fields, seen);
            }
            IType[] superInterfaces = hierarchy.getAllSuperInterfaces(type);
            for (IType superInterface : superInterfaces) {
                addInheritedMembers(superInterface, methods, fields, seen);
            }
        }

        result.put("methods", methods);
        result.put("fields", fields);
        result.put("nestedTypes", nestedTypes);
        result.put("count", methods.size() + fields.size() + nestedTypes.size());

        return result;
    }

    private void addMembers(IType type, List<Map<String, Object>> methods,
            List<Map<String, Object>> fields, List<Map<String, Object>> nestedTypes) throws JavaModelException {
        for (IMethod method : type.getMethods()) {
            methods.add(formatMethod(method, false));
        }
        for (IField field : type.getFields()) {
            fields.add(formatField(field, false));
        }
        for (IType nestedType : type.getTypes()) {
            nestedTypes.add(formatNestedType(nestedType));
        }
    }

    private void addInheritedMembers(IType type, List<Map<String, Object>> methods,
            List<Map<String, Object>> fields, Set<String> seen) throws JavaModelException {
        for (IMethod method : type.getMethods()) {
            String[] paramTypes = method.getParameterTypes();
            List<String> resolved = new ArrayList<>();
            for (String pt : paramTypes) {
                resolved.add(Signature.toString(pt));
            }
            String key = method.getElementName() + "|" + resolved;
            if (!seen.contains(key)) {
                seen.add(key);
                methods.add(formatMethod(method, true));
            }
        }
        for (IField field : type.getFields()) {
            String key = "field|" + field.getElementName();
            if (!seen.contains(key)) {
                seen.add(key);
                fields.add(formatField(field, true));
            }
        }
    }

    private Map<String, Object> formatMethod(IMethod method, boolean inherited) throws JavaModelException {
        Map<String, Object> info = new HashMap<>();
        info.put("name", method.getElementName());
        info.put("returnType", Signature.toString(method.getReturnType()));

        String[] paramNames = method.getParameterNames();
        String[] paramTypes = method.getParameterTypes();
        List<String> paramNameList = new ArrayList<>();
        List<String> paramTypeList = new ArrayList<>();
        for (int i = 0; i < paramNames.length; i++) {
            paramNameList.add(paramNames[i]);
            paramTypeList.add(Signature.toString(paramTypes[i]));
        }
        info.put("parameterNames", paramNameList);
        info.put("parameterTypes", paramTypeList);

        info.put("modifiers", getModifiersList(method.getFlags()));
        info.put("isConstructor", method.isConstructor());

        if (inherited) {
            info.put("inherited", true);
            if (method.getDeclaringType() != null) {
                info.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
            }
        }

        return info;
    }

    private Map<String, Object> formatField(IField field, boolean inherited) throws JavaModelException {
        Map<String, Object> info = new HashMap<>();
        info.put("name", field.getElementName());
        info.put("type", Signature.toString(field.getTypeSignature()));
        info.put("modifiers", getModifiersList(field.getFlags()));
        info.put("isEnumConstant", field.isEnumConstant());

        if (inherited) {
            info.put("inherited", true);
            if (field.getDeclaringType() != null) {
                info.put("declaringType", field.getDeclaringType().getFullyQualifiedName());
            }
        }

        return info;
    }

    private Map<String, Object> formatNestedType(IType type) throws JavaModelException {
        Map<String, Object> info = new HashMap<>();
        info.put("name", type.getElementName());
        info.put("kind", getTypeKind(type));
        info.put("modifiers", getModifiersList(type.getFlags()));
        return info;
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
        return modifiers;
    }
}
