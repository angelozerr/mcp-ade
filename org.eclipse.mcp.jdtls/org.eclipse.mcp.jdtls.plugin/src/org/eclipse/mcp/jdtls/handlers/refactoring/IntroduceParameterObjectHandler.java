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

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.refactoring.descriptors.IntroduceParameterObjectDescriptor;
import org.eclipse.jdt.internal.corext.refactoring.structure.IntroduceParameterObjectProcessor;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.introduceParameterObject" command.
 *
 * <p>Arguments: [{uri, line, character, className, parameterNames}]</p>
 *
 * <p>Introduces a parameter object using the JDT LTK refactoring engine
 * ({@link IntroduceParameterObjectProcessor}). Correctly handles:
 * <ul>
 *   <li>Creating a new parameter object class with fields, constructor, and getters</li>
 *   <li>Modifying the method signature to accept the parameter object</li>
 *   <li>Updating all call sites to create the parameter object</li>
 *   <li>Updating parameter references in the method body to use getter calls</li>
 * </ul>
 * </p>
 */
public class IntroduceParameterObjectHandler extends AbstractLTKRefactoringHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");
        String className = (String) params.get("className");
        List<String> parameterNames = (List<String>) params.get("parameterNames");

        if (uri == null || className == null || className.isEmpty()) {
            throw new RuntimeException("Missing required arguments: uri and className");
        }

        if (parameterNames == null || parameterNames.isEmpty()) {
            throw new RuntimeException("At least one parameter name must be specified");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

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

        IntroduceParameterObjectDescriptor descriptor = new IntroduceParameterObjectDescriptor();
        descriptor.setMethod(method);
        descriptor.setClassName(className);
        descriptor.setTopLevel(true);

        IntroduceParameterObjectProcessor processor = new IntroduceParameterObjectProcessor(descriptor);

        ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
        return executeRefactoring(refactoring, params, monitor);
    }
}
