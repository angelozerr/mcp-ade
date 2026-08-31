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
package org.eclipse.mcp.jdtls.handlers.search;

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
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.findUnreachableCode" command.
 *
 * <p>Arguments: [{uri}]</p>
 *
 * <p>Parses the given file with bindings resolved and inspects the
 * {@link IProblem} array for dead code and unreachable code problem IDs.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/FindUnreachableCodeTool.java">javalens-mcp FindUnreachableCodeTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class FindUnreachableCodeHandler implements ICommandHandler {

    private static final Set<Integer> UNREACHABLE_PROBLEM_IDS = new HashSet<>();

    static {
        UNREACHABLE_PROBLEM_IDS.add(IProblem.CodeCannotBeReached);
        UNREACHABLE_PROBLEM_IDS.add(IProblem.DeadCode);
        UNREACHABLE_PROBLEM_IDS.add(IProblem.UnnecessaryElse);
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

        List<Map<String, Object>> unreachableCode = new ArrayList<>();
        for (IProblem problem : astRoot.getProblems()) {
            if (UNREACHABLE_PROBLEM_IDS.contains(problem.getID())) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("message", problem.getMessage());
                entry.put("line", problem.getSourceLineNumber() - 1);
                entry.put("character", astRoot.getColumnNumber(problem.getSourceStart()));
                entry.put("problemId", problem.getID());
                unreachableCode.add(entry);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("count", unreachableCode.size());
        result.put("unreachableCode", unreachableCode);
        return result;
    }
}
