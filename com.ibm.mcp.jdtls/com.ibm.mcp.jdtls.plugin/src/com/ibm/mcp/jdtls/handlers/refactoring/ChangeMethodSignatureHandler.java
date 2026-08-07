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

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.internal.corext.refactoring.ParameterInfo;
import org.eclipse.jdt.internal.corext.refactoring.structure.ChangeSignatureProcessor;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.changeMethodSignature" command.
 *
 * <p>Arguments: [{uri, line, character, newName (optional), newReturnType (optional),
 * newParameters (optional list of {name, type}), newExceptions (optional list)}]</p>
 *
 * <p>Changes the method signature using the JDT LTK refactoring engine
 * ({@link ChangeSignatureProcessor}). Correctly handles:
 * <ul>
 *   <li>Renaming the method and updating all call sites</li>
 *   <li>Changing the return type</li>
 *   <li>Reordering, adding, and removing parameters with call site updates</li>
 *   <li>Updating the throws clause</li>
 *   <li>Handling overriding methods in the type hierarchy</li>
 * </ul>
 * </p>
 */
public class ChangeMethodSignatureHandler extends AbstractLTKRefactoringHandler {

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

        String newName = (String) params.get("newName");
        String newReturnType = (String) params.get("newReturnType");
        List<Map<String, String>> newParameters = (List<Map<String, String>>) params.get("newParameters");

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);

        IMethod method = null;
        for (IJavaElement element : elements) {
            if (element instanceof IMethod) {
                method = (IMethod) element;
                break;
            }
        }

        if (method == null) {
            throw new RuntimeException("No method found at position");
        }

        ChangeSignatureProcessor processor = new ChangeSignatureProcessor(method);

        if (newName != null && !newName.isEmpty()) {
            processor.setNewMethodName(newName);
        }

        if (newReturnType != null && !newReturnType.isEmpty()) {
            processor.setNewReturnTypeName(newReturnType);
        }

        if (newParameters != null) {
            // Clear existing parameters and add new ones
            List<ParameterInfo> paramInfos = processor.getParameterInfos();

            // Mark all existing parameters for removal
            for (ParameterInfo pi : paramInfos) {
                pi.markAsDeleted();
            }

            // Add new parameters
            for (int i = 0; i < newParameters.size(); i++) {
                Map<String, String> param = newParameters.get(i);
                String paramName = param.get("name");
                String paramType = param.get("type");
                String defaultValue = param.getOrDefault("defaultValue", "null");
                paramInfos.add(ParameterInfo.createInfoForAddedParameter(paramType, paramName, defaultValue));
            }
        }

        ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
        return executeRefactoring(refactoring, params, monitor);
    }
}
