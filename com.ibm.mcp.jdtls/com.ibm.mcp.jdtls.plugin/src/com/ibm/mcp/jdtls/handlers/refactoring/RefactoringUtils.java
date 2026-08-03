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

import java.lang.reflect.Method;

/**
 * Utilities for JDT LTK refactoring handlers.
 */
public final class RefactoringUtils {

    private static volatile Method resetWorkingCopiesMethod;

    private RefactoringUtils() {
    }

    /**
     * Explicitly discard working copies owned by a refactoring processor that
     * extends {@code SuperTypeRefactoringProcessor}.
     *
     * <p>These processors create internal working copies that are only cleaned up
     * when the processor is garbage collected (via {@code Cleaner}). In the MCP
     * context, successive tool calls happen faster than GC, so stale working copies
     * from a previous call interfere with binding resolution in the next call. This
     * method calls the protected {@code resetWorkingCopies()} via reflection to
     * force immediate cleanup.</p>
     *
     * @param processor a refactoring processor instance (must extend
     *                  {@code SuperTypeRefactoringProcessor})
     */
    public static void disposeWorkingCopies(Object processor) {
        try {
            Method method = resetWorkingCopiesMethod;
            if (method == null) {
                method = findResetWorkingCopiesMethod(processor.getClass());
                if (method == null) {
                    return;
                }
                resetWorkingCopiesMethod = method;
            }
            method.invoke(processor);
        } catch (Exception e) {
            // Best-effort cleanup
        }
    }

    private static Method findResetWorkingCopiesMethod(Class<?> clazz) {
        while (clazz != null) {
            try {
                Method method = clazz.getDeclaredMethod("resetWorkingCopies");
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }
}
