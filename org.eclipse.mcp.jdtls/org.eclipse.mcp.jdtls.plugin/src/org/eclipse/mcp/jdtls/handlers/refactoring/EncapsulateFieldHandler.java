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
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.internal.corext.refactoring.sef.SelfEncapsulateFieldRefactoring;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.encapsulateField" command.
 *
 * <p>Arguments: [{uri, line, character, getterName (optional), setterName (optional)}]</p>
 *
 * <p>Encapsulates a field using the JDT LTK refactoring engine
 * ({@link SelfEncapsulateFieldRefactoring}). Correctly handles:
 * <ul>
 *   <li>Generating getter and setter methods</li>
 *   <li>Making the field private</li>
 *   <li>Replacing all direct field accesses with getter/setter calls</li>
 *   <li>Distinguishing read vs. write accesses for correct replacement</li>
 *   <li>Handling accesses in the same class and external classes</li>
 * </ul>
 * </p>
 */
public class EncapsulateFieldHandler extends AbstractLTKRefactoringHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");

        if (uri == null) {
            throw new RuntimeException("Missing required argument: uri");
        }

        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);

        IField field = null;
        for (IJavaElement element : elements) {
            if (element instanceof IField) {
                field = (IField) element;
                break;
            }
        }

        if (field == null) {
            throw new RuntimeException("No field found at position");
        }

        SelfEncapsulateFieldRefactoring refactoring = new SelfEncapsulateFieldRefactoring(field);

        String getterName = (String) params.get("getterName");
        String setterName = (String) params.get("setterName");

        if (getterName != null && !getterName.isEmpty()) {
            refactoring.setGetterName(getterName);
        }
        if (setterName != null && !setterName.isEmpty()) {
            refactoring.setSetterName(setterName);
        }

        return executeRefactoring(refactoring, params, monitor);
    }
}
