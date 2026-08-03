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
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.PullUpRefactoringProcessor;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.pullUp" command.
 *
 * <p>Arguments: [{uri, line, character, memberNames}]</p>
 *
 * <p>Pulls up members from a subclass to its superclass using the JDT LTK
 * refactoring engine ({@link PullUpRefactoringProcessor}). Correctly handles:
 * <ul>
 *   <li>Access modifier adjustments</li>
 *   <li>Abstract method stub generation in subclasses</li>
 *   <li>Import updates in both source and target</li>
 *   <li>Type hierarchy consistency checks</li>
 * </ul>
 * </p>
 */
public class PullUpHandler extends AbstractLTKRefactoringHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");
        List<String> memberNames = (List<String>) params.get("memberNames");

        if (uri == null) {
            return createErrorResult("Missing required argument: uri");
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

        // Find the superclass
        ITypeHierarchy hierarchy = type.newSupertypeHierarchy(monitor);
        IType superclass = hierarchy.getSuperclass(type);
        if (superclass == null || superclass.getCompilationUnit() == null) {
            return createErrorResult("Type has no editable superclass (binary or no superclass)");
        }

        // Collect members to pull up
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
            return createErrorResult("No matching members found to pull up");
        }

        IMember[] members = membersToMove.toArray(new IMember[0]);
        CodeGenerationSettings settings = createCodeGenerationSettings(type.getCompilationUnit());
        PullUpRefactoringProcessor processor = new PullUpRefactoringProcessor(members, settings);
        processor.setDestinationType(superclass);
        processor.setMembersToMove(members);

        ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
        return executeRefactoring(refactoring, params, monitor);
    }
}
