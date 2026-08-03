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
package com.ibm.mcp.jdtls.handlers.refactoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.ExtractSupertypeProcessor;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.extractSuperclass" command.
 *
 * <p>Arguments: [{uri, line, character, superclassName, memberNames}]</p>
 *
 * <p>Extracts a superclass from a class using the JDT LTK refactoring engine
 * ({@link ExtractSupertypeProcessor}). Correctly handles:
 * <ul>
 *   <li>Creating a new abstract superclass file with proper imports</li>
 *   <li>Moving selected members (fields and methods) to the superclass</li>
 *   <li>Adding {@code extends} clause to the original class</li>
 *   <li>Updating references across the workspace</li>
 *   <li>Access modifier adjustments (private to protected)</li>
 * </ul>
 * </p>
 */
public class ExtractSuperclassHandler extends AbstractLTKRefactoringHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");
        String superclassName = (String) params.get("superclassName");
        List<String> memberNames = (List<String>) params.get("memberNames");

        if (uri == null || superclassName == null || superclassName.isEmpty()) {
            return createErrorResult("Missing required arguments: uri and superclassName");
        }

        if (memberNames == null || memberNames.isEmpty()) {
            return createErrorResult("At least one member name must be specified");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        IType type = JdtUtils.resolveTypeAtPosition(uri, line, character, monitor);
        if (type == null) {
            return createErrorResult("No type found at position");
        }

        if (type.getCompilationUnit() == null) {
            return createErrorResult("Cannot extract superclass: type is from a binary dependency, not a source file");
        }

        // Collect members to move
        List<IMember> membersToMove = new ArrayList<>();
        for (String memberName : memberNames) {
            IField field = type.getField(memberName);
            if (field != null && field.exists()) {
                membersToMove.add(field);
                continue;
            }
            IMethod[] methods = type.getMethods();
            for (IMethod method : methods) {
                if (method.getElementName().equals(memberName)) {
                    membersToMove.add(method);
                    break;
                }
            }
        }

        if (membersToMove.isEmpty()) {
            return createErrorResult("No matching members found to extract");
        }

        IMember[] members = membersToMove.toArray(new IMember[0]);
        CodeGenerationSettings settings = createCodeGenerationSettings(type.getCompilationUnit());
        ExtractSupertypeProcessor processor = new ExtractSupertypeProcessor(members, settings);
        processor.setTypeName(superclassName);
        processor.setMembersToMove(members);

        ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
        return executeRefactoring(refactoring, params, monitor);
    }
}
