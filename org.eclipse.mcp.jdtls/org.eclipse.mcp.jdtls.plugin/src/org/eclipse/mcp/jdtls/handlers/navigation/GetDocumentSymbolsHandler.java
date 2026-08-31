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
package org.eclipse.mcp.jdtls.handlers.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.getDocumentSymbols" command.
 *
 * <p>Arguments: [{uri}]</p>
 *
 * <p>Uses an {@link ASTParser} with an {@link ASTVisitor} to collect all
 * type, method, field, enum, annotation member, and enum constant
 * declarations. Returns a hierarchical list of symbols with nesting
 * support for inner types.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetDocumentSymbolsTool.java">javalens-mcp GetDocumentSymbolsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetDocumentSymbolsHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        List<Map<String, Object>> symbols = new ArrayList<>();
        Stack<List<Map<String, Object>>> containerStack = new Stack<>();
        containerStack.push(symbols);

        ast.accept(new ASTVisitor() {

            @Override
            public boolean visit(TypeDeclaration node) {
                Map<String, Object> symbol = new HashMap<>();
                symbol.put("name", node.getName().getIdentifier());
                symbol.put("kind", node.isInterface() ? "interface" : "class");
                symbol.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
                symbol.put("endLine", ast.getLineNumber(node.getStartPosition() + node.getLength()) - 1);

                List<Map<String, Object>> children = new ArrayList<>();
                symbol.put("children", children);

                containerStack.peek().add(symbol);
                containerStack.push(children);
                return true;
            }

            @Override
            public void endVisit(TypeDeclaration node) {
                containerStack.pop();
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                Map<String, Object> symbol = new HashMap<>();
                symbol.put("name", node.getName().getIdentifier());
                symbol.put("kind", "enum");
                symbol.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
                symbol.put("endLine", ast.getLineNumber(node.getStartPosition() + node.getLength()) - 1);

                List<Map<String, Object>> children = new ArrayList<>();
                symbol.put("children", children);

                containerStack.peek().add(symbol);
                containerStack.push(children);
                return true;
            }

            @Override
            public void endVisit(EnumDeclaration node) {
                containerStack.pop();
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                Map<String, Object> symbol = new HashMap<>();
                symbol.put("name", node.getName().getIdentifier());
                symbol.put("kind", node.isConstructor() ? "constructor" : "method");
                symbol.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
                symbol.put("endLine", ast.getLineNumber(node.getStartPosition() + node.getLength()) - 1);

                containerStack.peek().add(symbol);
                return false; // Don't visit children of methods
            }

            @Override
            @SuppressWarnings("rawtypes")
            public boolean visit(FieldDeclaration node) {
                List fragments = node.fragments();
                for (Object frag : fragments) {
                    if (frag instanceof VariableDeclarationFragment varFrag) {
                        Map<String, Object> symbol = new HashMap<>();
                        symbol.put("name", varFrag.getName().getIdentifier());
                        symbol.put("kind", "field");
                        symbol.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
                        symbol.put("endLine", ast.getLineNumber(node.getStartPosition() + node.getLength()) - 1);

                        containerStack.peek().add(symbol);
                    }
                }
                return false;
            }

            @Override
            public boolean visit(EnumConstantDeclaration node) {
                Map<String, Object> symbol = new HashMap<>();
                symbol.put("name", node.getName().getIdentifier());
                symbol.put("kind", "enumConstant");
                symbol.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
                symbol.put("endLine", ast.getLineNumber(node.getStartPosition() + node.getLength()) - 1);

                containerStack.peek().add(symbol);
                return false;
            }

            @Override
            public boolean visit(AnnotationTypeMemberDeclaration node) {
                Map<String, Object> symbol = new HashMap<>();
                symbol.put("name", node.getName().getIdentifier());
                symbol.put("kind", "annotationMember");
                symbol.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
                symbol.put("endLine", ast.getLineNumber(node.getStartPosition() + node.getLength()) - 1);

                containerStack.peek().add(symbol);
                return false;
            }
        });

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("symbols", symbols);
        return result;
    }
}
