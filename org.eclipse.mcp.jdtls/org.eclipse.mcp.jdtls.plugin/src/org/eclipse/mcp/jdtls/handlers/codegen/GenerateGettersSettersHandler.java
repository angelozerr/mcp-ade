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
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.mcp.jdtls.JdtUtils;
import org.eclipse.mcp.jdtls.handlers.refactoring.AbstractRefactoringHandler;

/**
 * Handler for "mcp.jdtls.generateGettersSetters" command.
 *
 * <p>Arguments: [{uri, line, character, fieldNames (optional), generateGetters (optional, default true),
 * generateSetters (optional, default true)}]</p>
 */
public class GenerateGettersSettersHandler extends AbstractRefactoringHandler {

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
        boolean generateGetters = params.get("generateGetters") == null || Boolean.TRUE.equals(params.get("generateGetters"));
        boolean generateSetters = params.get("generateSetters") == null || Boolean.TRUE.equals(params.get("generateSetters"));

        IType type = JdtUtils.resolveTypeAtPosition(uri, line, character, monitor);
        if (type == null) {
            throw new RuntimeException("No type found at position");
        }

        IField[] allFields = type.getFields();
        List<IField> targetFields = new ArrayList<>();

        for (IField field : allFields) {
            if (Flags.isStatic(field.getFlags()) && Flags.isFinal(field.getFlags())) {
                continue;
            }
            if (fieldNames == null || fieldNames.contains(field.getElementName())) {
                targetFields.add(field);
            }
        }

        if (targetFields.isEmpty()) {
            throw new RuntimeException("No eligible fields found for getter/setter generation");
        }

        StringBuilder code = new StringBuilder();
        String lineDelimiter = getLineDelimiter(type.getCompilationUnit());

        for (IField field : targetFields) {
            String fieldName = field.getElementName();
            String typeName = Signature.toString(field.getTypeSignature());
            String capitalizedName = capitalize(fieldName);
            boolean isBoolean = "boolean".equals(typeName);

            if (generateGetters) {
                String getterPrefix = isBoolean ? "is" : "get";
                if (!hasMethod(type, getterPrefix + capitalizedName, 0)) {
                    code.append(lineDelimiter);
                    code.append("\tpublic ").append(typeName).append(" ").append(getterPrefix).append(capitalizedName).append("() {").append(lineDelimiter);
                    code.append("\t\treturn ").append(fieldName).append(";").append(lineDelimiter);
                    code.append("\t}").append(lineDelimiter);
                }
            }

            if (generateSetters && !Flags.isFinal(field.getFlags())) {
                if (!hasMethod(type, "set" + capitalizedName, 1)) {
                    code.append(lineDelimiter);
                    code.append("\tpublic void set").append(capitalizedName).append("(").append(typeName).append(" ").append(fieldName).append(") {").append(lineDelimiter);
                    code.append("\t\tthis.").append(fieldName).append(" = ").append(fieldName).append(";").append(lineDelimiter);
                    code.append("\t}").append(lineDelimiter);
                }
            }
        }

        if (code.length() == 0) {
            throw new RuntimeException("All getters/setters already exist");
        }

        ICompilationUnit cu = type.getCompilationUnit();
        String source = cu.getSource();
        int insertOffset = findInsertOffset(type, source);

        return applyInsertEdit(uri, cu, source, insertOffset, code.toString(), isApply(params), monitor);
    }

    private boolean hasMethod(IType type, String methodName, int paramCount) throws Exception {
        return java.util.Arrays.stream(type.getMethods())
                .anyMatch(m -> m.getElementName().equals(methodName) && m.getParameterTypes().length == paramCount);
    }

    private int findInsertOffset(IType type, String source) throws Exception {
        ISourceRange typeRange = type.getSourceRange();
        int typeEnd = typeRange.getOffset() + typeRange.getLength();
        int closingBrace = source.lastIndexOf('}', typeEnd - 1);
        return closingBrace;
    }

    private String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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
