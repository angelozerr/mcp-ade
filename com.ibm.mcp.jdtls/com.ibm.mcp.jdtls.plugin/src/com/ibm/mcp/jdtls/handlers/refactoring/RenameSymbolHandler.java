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
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameFieldProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameLocalVariableProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.MethodChecks;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameNonVirtualMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameVirtualMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeProcessor;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.renameSymbol" command.
 *
 * <p>Arguments: [{uri, line, character, newName}]</p>
 *
 * <p>Renames a Java element using the JDT LTK refactoring engine. Correctly handles:
 * <ul>
 *   <li>Type renames (including compilation unit rename)</li>
 *   <li>Method renames (including overriding methods in hierarchy)</li>
 *   <li>Field renames (including getter/setter updates)</li>
 *   <li>Local variable and parameter renames</li>
 *   <li>Import statement updates across the workspace</li>
 *   <li>Qualified name updates in strings and comments (optional)</li>
 * </ul>
 * </p>
 */
public class RenameSymbolHandler extends AbstractLTKRefactoringHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");
        String newName = (String) params.get("newName");

        if (uri == null || newName == null || newName.isEmpty()) {
            throw new RuntimeException("Missing required arguments: uri and newName");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);
        if (elements == null || elements.length == 0) {
            throw new RuntimeException("No element found at position");
        }

        IJavaElement element = elements[0];
        String oldName = element.getElementName();

        if (oldName.equals(newName)) {
            throw new RuntimeException("New name is the same as the old name");
        }

        JavaRenameProcessor processor = createRenameProcessor(element);
        if (processor == null) {
            throw new RuntimeException("Unsupported element type for rename: " + element.getClass().getSimpleName());
        }

        processor.setNewElementName(newName);

        RenameRefactoring refactoring = new RenameRefactoring(processor);
        return executeRefactoring(refactoring, params, monitor);
    }

    private JavaRenameProcessor createRenameProcessor(IJavaElement element) throws Exception {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> new RenameTypeProcessor((IType) element);
            case IJavaElement.METHOD -> {
                IMethod m = (IMethod) element;
                yield MethodChecks.isVirtual(m)
                        ? new RenameVirtualMethodProcessor(m)
                        : new RenameNonVirtualMethodProcessor(m);
            }
            case IJavaElement.FIELD -> new RenameFieldProcessor((IField) element);
            case IJavaElement.LOCAL_VARIABLE -> new RenameLocalVariableProcessor((ILocalVariable) element);
            default -> null;
        };
    }
}
