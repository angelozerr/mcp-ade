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
package org.eclipse.mcp.jdtls.handlers.refactoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.convertToRecord" command.
 *
 * <p>Arguments: [{uri, line, character, apply (optional)}]</p>
 *
 * <p>Converts a simple POJO class to a Java record (Java 16+).
 * A class is eligible if it has no explicit superclass, is not abstract,
 * and is not an enum/interface/annotation/record.</p>
 */
public class ConvertToRecordHandler extends AbstractRefactoringHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");

        if (uri == null) {
            throw new RuntimeException("Missing required argument: uri");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        IType type = JdtUtils.resolveTypeAtPosition(uri, line, character, monitor);
        if (type == null) {
            throw new RuntimeException("No type found at position");
        }

        String error = checkEligibility(type);
        if (error != null) {
            throw new RuntimeException(error);
        }

        IField[] fields = getInstanceFields(type);
        if (fields.length == 0) {
            throw new RuntimeException("Class has no instance fields to convert to record components");
        }

        ICompilationUnit cu = type.getCompilationUnit();
        String source = cu.getSource();
        String lineDelimiter = source.contains("\r\n") ? "\r\n" : "\n";

        String newSource = buildRecordSource(type, fields, source, lineDelimiter);

        List<Map<String, Object>> edits = createWholeFileEdit(uri, source, newSource);

        boolean apply = isApply(params);
        if (apply) {
            cu.getBuffer().setContents(newSource);
            cu.save(monitor, true);
        }

        return createSuccessResult(edits, apply);
    }

    private String checkEligibility(IType type) throws Exception {
        if (type.isInterface()) {
            return "Cannot convert an interface to a record";
        }
        if (type.isEnum()) {
            return "Cannot convert an enum to a record";
        }
        if (type.isRecord()) {
            return "Type is already a record";
        }
        if (type.isAnnotation()) {
            return "Cannot convert an annotation type to a record";
        }
        if (Flags.isAbstract(type.getFlags())) {
            return "Cannot convert an abstract class to a record";
        }
        String superclassName = type.getSuperclassName();
        if (superclassName != null && !"Object".equals(superclassName) && !"java.lang.Object".equals(superclassName)) {
            return "Cannot convert a class with a superclass to a record (extends " + superclassName + ")";
        }
        return null;
    }

    private IField[] getInstanceFields(IType type) throws Exception {
        return Arrays.stream(type.getFields())
                .filter(f -> {
                    try {
                        return !Flags.isStatic(f.getFlags());
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toArray(IField[]::new);
    }

    private String buildRecordSource(IType type, IField[] fields, String source, String lineDelimiter)
            throws Exception {
        ISourceRange typeRange = type.getSourceRange();
        int typeStart = typeRange.getOffset();
        int typeEnd = typeStart + typeRange.getLength();

        String beforeType = source.substring(0, typeStart);
        String afterType = source.substring(typeEnd);

        StringBuilder record = new StringBuilder();

        int flags = type.getFlags();
        if (Flags.isPublic(flags)) {
            record.append("public ");
        } else if (Flags.isProtected(flags)) {
            record.append("protected ");
        }

        record.append("record ").append(type.getElementName()).append("(");

        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                record.append(", ");
            }
            record.append(Signature.toString(fields[i].getTypeSignature()));
            record.append(" ");
            record.append(fields[i].getElementName());
        }

        record.append(")");

        String[] superInterfaces = type.getSuperInterfaceNames();
        if (superInterfaces != null && superInterfaces.length > 0) {
            record.append(" implements ").append(String.join(", ", superInterfaces));
        }

        record.append(" {").append(lineDelimiter);

        Set<String> fieldNames = Arrays.stream(fields)
                .map(IField::getElementName)
                .collect(Collectors.toSet());

        List<IMethod> methodsToKeep = collectMethodsToKeep(type, fields, fieldNames);
        for (IMethod method : methodsToKeep) {
            ISourceRange methodRange = method.getSourceRange();
            String methodSource = source.substring(methodRange.getOffset(),
                    methodRange.getOffset() + methodRange.getLength());
            record.append(lineDelimiter);
            record.append("\t").append(methodSource.strip()).append(lineDelimiter);
        }

        record.append("}").append(lineDelimiter);

        return beforeType + record.toString() + afterType;
    }

    private List<IMethod> collectMethodsToKeep(IType type, IField[] fields, Set<String> fieldNames)
            throws Exception {
        List<IMethod> result = new ArrayList<>();
        for (IMethod method : type.getMethods()) {
            if (method.isConstructor() && isCanonicalConstructor(method, fields)) {
                continue;
            }
            if (isSimpleAccessor(method, fieldNames, type)) {
                continue;
            }
            result.add(method);
        }
        return result;
    }

    private boolean isCanonicalConstructor(IMethod method, IField[] fields) throws Exception {
        if (!method.isConstructor()) {
            return false;
        }
        String[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != fields.length) {
            return false;
        }
        for (int i = 0; i < fields.length; i++) {
            if (!paramTypes[i].equals(fields[i].getTypeSignature())) {
                return false;
            }
        }
        return true;
    }

    private boolean isSimpleAccessor(IMethod method, Set<String> fieldNames, IType type) throws Exception {
        if (method.isConstructor()) {
            return false;
        }
        if (method.getParameterTypes().length != 0) {
            return false;
        }
        String name = method.getElementName();
        if (fieldNames.contains(name)) {
            return isSimpleReturn(method, name, type);
        }
        for (String fieldName : fieldNames) {
            String getterName = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            String isName = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
            if (name.equals(getterName) || name.equals(isName)) {
                return isSimpleReturn(method, fieldName, type);
            }
        }
        return false;
    }

    private boolean isSimpleReturn(IMethod method, String fieldName, IType type) throws Exception {
        ISourceRange range = method.getSourceRange();
        String source = type.getCompilationUnit().getSource();
        String body = source.substring(range.getOffset(), range.getOffset() + range.getLength());
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.contains("return " + fieldName + " ;") ||
                normalized.contains("return " + fieldName + ";") ||
                normalized.contains("return this . " + fieldName + " ;") ||
                normalized.contains("return this." + fieldName + ";") ||
                normalized.contains("return this . " + fieldName + ";");
    }
}
