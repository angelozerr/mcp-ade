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
package com.ibm.mcp.jdtls.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.typeHierarchy" command.
 *
 * <p>Arguments: [uri, line, character] or [fullyQualifiedName]</p>
 *
 * <p>Returns the type hierarchy (supertypes and subtypes) for the given type.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetTypeHierarchyTool.java">javalens-mcp GetTypeHierarchyTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class TypeHierarchyHandler implements ICommandHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        IType type = JdtUtils.resolveType(arguments, monitor);
        if (type == null || !type.exists()) {
            throw new RuntimeException("Type not found");
        }

        ITypeHierarchy hierarchy = type.newTypeHierarchy(monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("type", formatType(type));
        result.put("supertypes", formatTypes(hierarchy.getAllSuperclasses(type)));
        result.put("superInterfaces", formatTypes(hierarchy.getAllSuperInterfaces(type)));
        result.put("subtypes", formatTypes(hierarchy.getAllSubtypes(type)));
        return result;
    }

    private Map<String, Object> formatType(IType type) throws JavaModelException {
        Map<String, Object> info = new HashMap<>();
        info.put("name", type.getElementName());
        info.put("fullyQualifiedName", type.getFullyQualifiedName());
        info.put("isInterface", type.isInterface());
        if (type.getResource() != null) {
            info.put("uri", JdtUtils.toFileUri(type.getResource()));
        }
        return info;
    }

    private List<Map<String, Object>> formatTypes(IType[] types) throws JavaModelException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (IType type : types) {
            result.add(formatType(type));
        }
        return result;
    }
}
