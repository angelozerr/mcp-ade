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
package org.eclipse.mcp.jdtls.handlers.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.mcp.jdtls.JdtUtils;
import org.eclipse.mcp.jdtls.handlers.refactoring.AbstractRefactoringHandler;

/**
 * Handler for "mcp.jdtls.generateDelegateMethods" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Generates delegate methods for the field at the given position.
 * Each public method of the field's type gets a forwarding method in the enclosing class.</p>
 */
public class GenerateDelegateMethodsHandler extends AbstractRefactoringHandler {

    private static final List<String> OBJECT_METHODS = List.of(
            "getClass", "hashCode", "equals", "clone", "toString",
            "notify", "notifyAll", "wait", "finalize");

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");

        if (uri == null) {
            throw new RuntimeException("Missing required argument: uri");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);

        IField field = null;
        for (IJavaElement element : elements) {
            if (element instanceof IField) {
                field = (IField) element;
                break;
            }
        }

        if (field == null) {
            throw new RuntimeException("No field found at position");
        }

        IType enclosingType = field.getDeclaringType();
        IType fieldType = resolveFieldType(field);

        if (fieldType == null) {
            throw new RuntimeException("Cannot resolve field type: " + Signature.toString(field.getTypeSignature()));
        }

        List<IMethod> delegateMethods = collectDelegateMethods(fieldType, enclosingType);

        if (delegateMethods.isEmpty()) {
            throw new RuntimeException("No methods available for delegation (all already exist or type has no public methods)");
        }

        String fieldName = field.getElementName();
        String lineDelimiter = getLineDelimiter(cu);
        StringBuilder code = new StringBuilder();

        for (IMethod method : delegateMethods) {
            code.append(lineDelimiter);
            code.append(generateDelegateMethod(method, fieldName, lineDelimiter));
        }

        String source = cu.getSource();
        int insertOffset = findInsertOffset(enclosingType, source);

        return applyInsertEdit(uri, cu, source, insertOffset, code.toString(), isApply(params), monitor);
    }

    private IType resolveFieldType(IField field) throws Exception {
        IType declaringType = field.getDeclaringType();
        String typeSig = field.getTypeSignature();
        String simpleName = Signature.toString(typeSig);

        String[][] resolved = declaringType.resolveType(simpleName);
        if (resolved != null && resolved.length > 0) {
            String fqn = resolved[0][0].isEmpty() ? resolved[0][1] : resolved[0][0] + "." + resolved[0][1];
            return field.getJavaProject().findType(fqn);
        }
        return null;
    }

    private List<IMethod> collectDelegateMethods(IType fieldType, IType enclosingType) throws Exception {
        List<IMethod> result = new ArrayList<>();
        for (IMethod method : fieldType.getMethods()) {
            if (!Flags.isPublic(method.getFlags())) {
                continue;
            }
            if (Flags.isStatic(method.getFlags())) {
                continue;
            }
            if (method.isConstructor()) {
                continue;
            }
            if (OBJECT_METHODS.contains(method.getElementName())) {
                continue;
            }
            if (hasMatchingMethod(enclosingType, method)) {
                continue;
            }
            result.add(method);
        }
        return result;
    }

    private boolean hasMatchingMethod(IType type, IMethod method) throws Exception {
        String name = method.getElementName();
        int paramCount = method.getParameterTypes().length;
        for (IMethod m : type.getMethods()) {
            if (m.getElementName().equals(name) && m.getParameterTypes().length == paramCount) {
                return true;
            }
        }
        return false;
    }

    private String generateDelegateMethod(IMethod method, String fieldName, String lineDelimiter) throws Exception {
        StringBuilder sb = new StringBuilder();
        String returnType = Signature.toString(method.getReturnType());
        String methodName = method.getElementName();
        boolean isVoid = "void".equals(returnType);

        sb.append("\tpublic ").append(returnType).append(" ").append(methodName).append("(");

        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(Signature.toString(paramTypes[i])).append(" ").append(paramNames[i]);
        }

        sb.append(") {").append(lineDelimiter);
        sb.append("\t\t");
        if (!isVoid) {
            sb.append("return ");
        }
        sb.append(fieldName).append(".").append(methodName).append("(");
        for (int i = 0; i < paramNames.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(paramNames[i]);
        }
        sb.append(");").append(lineDelimiter);
        sb.append("\t}").append(lineDelimiter);

        return sb.toString();
    }

    private int findInsertOffset(IType type, String source) throws Exception {
        ISourceRange typeRange = type.getSourceRange();
        int typeEnd = typeRange.getOffset() + typeRange.getLength();
        return source.lastIndexOf('}', typeEnd - 1);
    }

    private String getLineDelimiter(ICompilationUnit cu) {
        try {
            String source = cu.getSource();
            if (source.contains("\r\n")) {
                return "\r\n";
            }
        } catch (Exception e) {
            // fallback
        }
        return "\n";
    }
}
