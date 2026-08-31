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
 * Handler for "mcp.jdtls.generateConstructor" command.
 *
 * <p>Arguments: [{uri, line, character, fieldNames (optional — all fields if not specified),
 * generateDefault (optional, default false)}]</p>
 */
public class GenerateConstructorHandler extends AbstractRefactoringHandler {

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
        boolean generateDefault = Boolean.TRUE.equals(params.get("generateDefault"));

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

        ICompilationUnit cu = type.getCompilationUnit();
        String source = cu.getSource();
        String lineDelimiter = getLineDelimiter(cu);
        String typeName = type.getElementName();

        StringBuilder code = new StringBuilder();

        if (generateDefault) {
            code.append(lineDelimiter);
            code.append("\tpublic ").append(typeName).append("() {").append(lineDelimiter);
            code.append("\t}").append(lineDelimiter);
        }

        if (!targetFields.isEmpty()) {
            code.append(lineDelimiter);
            code.append("\tpublic ").append(typeName).append("(");

            for (int i = 0; i < targetFields.size(); i++) {
                if (i > 0) {
                    code.append(", ");
                }
                IField field = targetFields.get(i);
                code.append(Signature.toString(field.getTypeSignature()));
                code.append(" ").append(field.getElementName());
            }

            code.append(") {").append(lineDelimiter);

            for (IField field : targetFields) {
                String fieldName = field.getElementName();
                code.append("\t\tthis.").append(fieldName).append(" = ").append(fieldName).append(";").append(lineDelimiter);
            }

            code.append("\t}").append(lineDelimiter);
        }

        if (code.length() == 0) {
            throw new RuntimeException("No constructor to generate (no eligible fields)");
        }

        int insertOffset = findInsertOffset(type, source);

        return applyInsertEdit(uri, cu, source, insertOffset, code.toString(), isApply(params), monitor);
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
