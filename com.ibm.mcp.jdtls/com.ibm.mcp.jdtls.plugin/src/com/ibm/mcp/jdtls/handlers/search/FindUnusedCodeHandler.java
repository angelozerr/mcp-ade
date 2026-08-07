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
package com.ibm.mcp.jdtls.handlers.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.findUnusedCode" command.
 *
 * <p>Arguments: [{uri}]</p>
 *
 * <p>Parses the given file with bindings resolved and inspects the
 * {@link IProblem} array for unused-related problem IDs such as unused private
 * fields, methods, types, imports, and local variables.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindUnusedCodeTool.java">javalens-mcp FindUnusedCodeTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindUnusedCodeHandler implements ICommandHandler {

    private static final Set<Integer> UNUSED_PROBLEM_IDS = new HashSet<>();

    static {
        UNUSED_PROBLEM_IDS.add(IProblem.UnusedPrivateField);
        UNUSED_PROBLEM_IDS.add(IProblem.UnusedPrivateMethod);
        UNUSED_PROBLEM_IDS.add(IProblem.UnusedPrivateType);
        UNUSED_PROBLEM_IDS.add(IProblem.UnusedImport);
        UNUSED_PROBLEM_IDS.add(IProblem.LocalVariableIsNeverUsed);
        UNUSED_PROBLEM_IDS.add(IProblem.ArgumentIsNeverUsed);
        UNUSED_PROBLEM_IDS.add(IProblem.UnusedPrivateConstructor);
        UNUSED_PROBLEM_IDS.add(IProblem.UnusedTypeParameter);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }
        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");
        if (uri == null) {
            throw new RuntimeException("Missing uri parameter");
        }

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit astRoot = (CompilationUnit) parser.createAST(monitor);

        List<Map<String, Object>> unusedElements = new ArrayList<>();
        for (IProblem problem : astRoot.getProblems()) {
            if (UNUSED_PROBLEM_IDS.contains(problem.getID())) {
                Map<String, Object> element = new HashMap<>();
                element.put("message", problem.getMessage());
                element.put("line", problem.getSourceLineNumber() - 1);
                element.put("character", astRoot.getColumnNumber(problem.getSourceStart()));
                unusedElements.add(element);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("count", unusedElements.size());
        result.put("unusedElements", unusedElements);
        return result;
    }
}
