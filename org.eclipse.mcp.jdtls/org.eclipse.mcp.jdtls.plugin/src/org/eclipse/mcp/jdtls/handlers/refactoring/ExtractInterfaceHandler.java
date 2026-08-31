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
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.ExtractInterfaceProcessor;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.extractInterface" command.
 *
 * <p>Arguments: [{uri, line, character, interfaceName, methodNames}]</p>
 *
 * <p>Extracts an interface from a class using the JDT LTK refactoring engine
 * ({@link ExtractInterfaceProcessor}). Correctly handles:
 * <ul>
 *   <li>Creating a new interface file with proper imports</li>
 *   <li>Adding {@code implements} clause to the original class</li>
 *   <li>Updating type references across the workspace</li>
 *   <li>Generic type parameter handling</li>
 * </ul>
 * </p>
 */
public class ExtractInterfaceHandler extends AbstractLTKRefactoringHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");
        String interfaceName = (String) params.get("interfaceName");
        List<String> methodNames = (List<String>) params.get("methodNames");

        if (uri == null || interfaceName == null || interfaceName.isEmpty()) {
            throw new RuntimeException("Missing required arguments: uri and interfaceName");
        }

        if (methodNames == null || methodNames.isEmpty()) {
            throw new RuntimeException("At least one method name must be specified");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        IType type = JdtUtils.resolveTypeAtPosition(uri, line, character, monitor);
        if (type == null) {
            throw new RuntimeException("No type found at position");
        }

        if (type.getCompilationUnit() == null) {
            throw new RuntimeException("Cannot extract interface: type is from a binary dependency, not a source file");
        }

        if (type.isInterface()) {
            throw new RuntimeException("Cannot extract interface from an interface");
        }

        CodeGenerationSettings settings = createCodeGenerationSettings(type.getCompilationUnit());
        ExtractInterfaceProcessor processor = new ExtractInterfaceProcessor(type, settings);
        processor.setTypeName(interfaceName);

        // Resolve methods to extract
        List<IMethod> extractMethods = new ArrayList<>();
        IMethod[] methods = type.getMethods();
        for (IMethod method : methods) {
            if (methodNames.contains(method.getElementName())) {
                extractMethods.add(method);
            }
        }

        if (extractMethods.isEmpty()) {
            throw new RuntimeException("No matching methods found to extract");
        }

        processor.setExtractedMembers(extractMethods.toArray(new IMethod[0]));

        ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
        return executeRefactoring(refactoring, params, monitor);
    }
}
