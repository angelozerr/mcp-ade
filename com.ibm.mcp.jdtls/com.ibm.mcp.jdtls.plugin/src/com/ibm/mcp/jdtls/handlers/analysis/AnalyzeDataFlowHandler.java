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
package com.ibm.mcp.jdtls.handlers.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

import com.ibm.mcp.jdtls.ICommandHandler;
import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.analyzeDataFlow" command.
 *
 * <p>Arguments: [{uri, line, character}] to resolve a method.</p>
 *
 * <p>Uses ASTParser and ASTVisitor on the MethodDeclaration to track
 * variable definitions, reads, and writes for each local variable and parameter.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/AnalyzeDataFlowTool.java">javalens-mcp AnalyzeDataFlowTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class AnalyzeDataFlowHandler implements ICommandHandler {

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

        // Parse the compilation unit AST with bindings
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        // Find the MethodDeclaration at the given offset
        final IMethod targetMethod = method;
        final MethodDeclaration[] foundMethod = {null};

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getName().getIdentifier().equals(targetMethod.getElementName())) {
                    int methodStart = node.getStartPosition();
                    int methodEnd = methodStart + node.getLength();
                    if (offset >= methodStart && offset <= methodEnd) {
                        foundMethod[0] = node;
                    }
                }
                return false;
            }
        });

        if (foundMethod[0] == null) {
            throw new RuntimeException("Method declaration not found in AST");
        }

        MethodDeclaration methodDecl = foundMethod[0];

        // Track variables: key -> variable binding key
        // value -> info about reads/writes
        Map<String, VariableInfo> variableMap = new LinkedHashMap<>();

        // Collect parameters
        for (Object param : methodDecl.parameters()) {
            if (param instanceof SingleVariableDeclaration svd) {
                String varName = svd.getName().getIdentifier();
                String varType = svd.getType().toString();
                int defLine = ast.getLineNumber(svd.getStartPosition());
                IVariableBinding binding = svd.resolveBinding();
                String key = binding != null ? binding.getKey() : varName;
                VariableInfo info = new VariableInfo(varName, varType, defLine, true);
                variableMap.put(key, info);
            }
        }

        // First pass: collect local variable declarations
        methodDecl.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationStatement node) {
                String varType = node.getType().toString();
                for (Object frag : node.fragments()) {
                    if (frag instanceof VariableDeclarationFragment vdf) {
                        String varName = vdf.getName().getIdentifier();
                        int defLine = ast.getLineNumber(vdf.getStartPosition());
                        IVariableBinding binding = vdf.resolveBinding();
                        String key = binding != null ? binding.getKey() : varName;
                        VariableInfo info = new VariableInfo(varName, varType, defLine, false);
                        variableMap.put(key, info);
                    }
                }
                return true;
            }
        });

        // Second pass: track reads and writes
        methodDecl.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                if (binding instanceof IVariableBinding varBinding) {
                    String key = varBinding.getKey();
                    VariableInfo info = variableMap.get(key);
                    if (info != null) {
                        int nodeLine = ast.getLineNumber(node.getStartPosition());
                        if (isWriteAccess(node)) {
                            info.writes.add(nodeLine);
                        } else {
                            info.reads.add(nodeLine);
                        }
                    }
                }
                return true;
            }
        });

        // Build result
        List<Map<String, Object>> variables = new ArrayList<>();
        for (VariableInfo info : variableMap.values()) {
            Map<String, Object> varInfo = new HashMap<>();
            varInfo.put("name", info.name);
            varInfo.put("type", info.type);
            varInfo.put("definitionLine", info.definitionLine);
            varInfo.put("isParameter", info.isParameter);
            varInfo.put("reads", info.reads);
            varInfo.put("writes", info.writes);
            variables.add(varInfo);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("method", method.getElementName());
        result.put("variables", variables);
        return result;
    }

    private boolean isWriteAccess(SimpleName node) {
        if (node.getParent() instanceof Assignment assignment) {
            return assignment.getLeftHandSide() == node;
        }
        if (node.getParent() instanceof PostfixExpression postfix) {
            return postfix.getOperand() == node;
        }
        if (node.getParent() instanceof PrefixExpression prefix) {
            PrefixExpression.Operator op = prefix.getOperator();
            return op == PrefixExpression.Operator.INCREMENT
                    || op == PrefixExpression.Operator.DECREMENT;
        }
        return false;
    }

    private static class VariableInfo {
        final String name;
        final String type;
        final int definitionLine;
        final boolean isParameter;
        final List<Integer> reads = new ArrayList<>();
        final List<Integer> writes = new ArrayList<>();

        VariableInfo(String name, String type, int definitionLine, boolean isParameter) {
            this.name = name;
            this.type = type;
            this.definitionLine = definitionLine;
            this.isParameter = isParameter;
        }
    }
}
