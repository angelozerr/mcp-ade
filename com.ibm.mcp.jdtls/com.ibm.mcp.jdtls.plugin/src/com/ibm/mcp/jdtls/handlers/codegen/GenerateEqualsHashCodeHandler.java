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
package com.ibm.mcp.jdtls.handlers.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;

import com.ibm.mcp.jdtls.JdtUtils;
import com.ibm.mcp.jdtls.handlers.refactoring.AbstractRefactoringHandler;

/**
 * Handler for "mcp.jdtls.generateEqualsHashCode" command.
 *
 * <p>Arguments: [{uri, line, character, fieldNames (optional — all instance fields if not specified)}]</p>
 *
 * <p>Generates {@code equals()} and {@code hashCode()} methods using {@code java.util.Objects}.</p>
 */
public class GenerateEqualsHashCodeHandler extends AbstractRefactoringHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");

        if (uri == null) {
            throw new RuntimeException("Missing required argument: uri");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        List<String> fieldNames = (List<String>) params.get("fieldNames");

        IType type = JdtUtils.resolveTypeAtPosition(uri, line, character, monitor);
        if (type == null) {
            throw new RuntimeException("No type found at position");
        }

        IField[] allFields = type.getFields();
        List<IField> targetFields = new ArrayList<>();

        for (IField field : allFields) {
            if (Flags.isStatic(field.getFlags())) {
                continue;
            }
            if (fieldNames == null || fieldNames.contains(field.getElementName())) {
                targetFields.add(field);
            }
        }

        if (targetFields.isEmpty()) {
            throw new RuntimeException("No eligible fields found for equals/hashCode generation");
        }

        ICompilationUnit cu = type.getCompilationUnit();
        String source = cu.getSource();
        String lineDelimiter = getLineDelimiter(cu);
        String typeName = type.getElementName();

        StringBuilder code = new StringBuilder();

        // Generate hashCode()
        code.append(lineDelimiter);
        code.append("\t@Override").append(lineDelimiter);
        code.append("\tpublic int hashCode() {").append(lineDelimiter);
        code.append("\t\treturn java.util.Objects.hash(");
        for (int i = 0; i < targetFields.size(); i++) {
            if (i > 0) {
                code.append(", ");
            }
            code.append(targetFields.get(i).getElementName());
        }
        code.append(");").append(lineDelimiter);
        code.append("\t}").append(lineDelimiter);

        // Generate equals()
        code.append(lineDelimiter);
        code.append("\t@Override").append(lineDelimiter);
        code.append("\tpublic boolean equals(Object obj) {").append(lineDelimiter);
        code.append("\t\tif (this == obj) return true;").append(lineDelimiter);
        code.append("\t\tif (obj == null || getClass() != obj.getClass()) return false;").append(lineDelimiter);
        code.append("\t\t").append(typeName).append(" other = (").append(typeName).append(") obj;").append(lineDelimiter);
        code.append("\t\treturn ");

        for (int i = 0; i < targetFields.size(); i++) {
            if (i > 0) {
                code.append(lineDelimiter).append("\t\t\t&& ");
            }
            IField field = targetFields.get(i);
            String fieldName = field.getElementName();
            String sigType = Signature.toString(field.getTypeSignature());

            if (isPrimitive(sigType)) {
                code.append(fieldName).append(" == other.").append(fieldName);
            } else {
                code.append("java.util.Objects.equals(").append(fieldName).append(", other.").append(fieldName).append(")");
            }
        }

        code.append(";").append(lineDelimiter);
        code.append("\t}").append(lineDelimiter);

        int insertOffset = findInsertOffset(type, source);

        List<Map<String, Object>> edits = createInsertEdit(uri, source, insertOffset, code.toString());
        return createSuccessResult(edits);
    }

    private boolean isPrimitive(String typeName) {
        return switch (typeName) {
            case "byte", "short", "int", "long", "float", "double", "char", "boolean" -> true;
            default -> false;
        };
    }

    private int findInsertOffset(IType type, String source) throws Exception {
        ISourceRange typeRange = type.getSourceRange();
        int typeEnd = typeRange.getOffset() + typeRange.getLength();
        int closingBrace = source.lastIndexOf('}', typeEnd - 1);
        return closingBrace;
    }

    private String getLineDelimiter(ICompilationUnit cu) {
        try {
            String s = cu.getSource();
            if (s.contains("\r\n")) {
                return "\r\n";
            }
        } catch (Exception e) {
            // fallback
        }
        return "\n";
    }
}
