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
package com.ibm.mcp.jdtls.handlers.navigation;

import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Base class for handlers that resolve an element at a given position in a file.
 *
 * <p>Parses arguments {@code {uri, line, character}}, obtains the
 * {@link ICompilationUnit}, computes the offset and calls
 * {@link ICompilationUnit#codeSelect(int, int)} to resolve the
 * {@link IJavaElement}s at that position.</p>
 *
 * <p>Subclasses implement
 * {@link #handleElements(IJavaElement[], ICompilationUnit, int, IProgressMonitor)}
 * to produce the result.</p>
 */
public abstract class AbstractPositionHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");
        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        int offset = JdtUtils.getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);

        return handleElements(elements, cu, offset, monitor);
    }

    /**
     * Process the resolved elements.
     *
     * @param elements the Java elements resolved via {@code codeSelect}
     * @param cu       the compilation unit
     * @param offset   the character offset in the source
     * @param monitor  the progress monitor
     * @return the result object to return to the caller
     * @throws Exception if processing fails
     */
    protected abstract Object handleElements(IJavaElement[] elements, ICompilationUnit cu, int offset,
            IProgressMonitor monitor) throws Exception;
}
