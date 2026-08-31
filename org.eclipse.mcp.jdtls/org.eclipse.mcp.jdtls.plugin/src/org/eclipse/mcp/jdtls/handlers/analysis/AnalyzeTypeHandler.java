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
package org.eclipse.mcp.jdtls.handlers.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.DoStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.analyzeType" command.
 *
 * <p>Arguments: [{fullyQualifiedName}] or [{uri, line, character}]</p>
 *
 * <p>Comprehensive type analysis: type hierarchy, member counts,
 * references, and per-method complexity.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/AnalyzeTypeTool.java">javalens-mcp AnalyzeTypeTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class AnalyzeTypeHandler implements ICommandHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        IType type = JdtUtils.resolveType(arguments, monitor);
        if (type == null || !type.exists()) {
            throw new RuntimeException("Type not found");
        }

        // Type hierarchy
        ITypeHierarchy hierarchy = type.newTypeHierarchy(monitor);
        IType[] supertypes = hierarchy.getAllSuperclasses(type);
        IType[] subtypes = hierarchy.getAllSubtypes(type);

        // Count members
        IMethod[] methods = type.getMethods();
        IField[] fields = type.getFields();
        IType[] nestedTypes = type.getTypes();

        // Find references
        int referenceCount = countReferences(type, monitor);

        // Build members list with complexity
        List<Map<String, Object>> members = buildMembersList(type, monitor);

        // Determine kind
        String kind;
        if (type.isInterface()) {
            kind = "interface";
        } else if (type.isEnum()) {
            kind = "enum";
        } else if (type.isAnnotation()) {
            kind = "annotation";
        } else if (type.isRecord()) {
            kind = "record";
        } else {
            kind = "class";
        }

        Map<String, Object> result = new HashMap<>();
        result.put("type", type.getFullyQualifiedName());
        result.put("kind", kind);
        result.put("supertypes", supertypes.length);
        result.put("subtypes", subtypes.length);
        result.put("methods", methods.length);
        result.put("fields", fields.length);
        result.put("nestedTypes", nestedTypes.length);
        result.put("references", referenceCount);
        result.put("members", members);
        return result;
    }

    private int countReferences(IType type, IProgressMonitor monitor) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
                type,
                IJavaSearchConstants.REFERENCES);

        if (pattern == null) {
            return 0;
        }

        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        int[] count = {0};

        SearchEngine engine = new SearchEngine();
        engine.search(
                pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        count[0]++;
                    }
                },
                monitor);

        return count[0];
    }

    private List<Map<String, Object>> buildMembersList(IType type, IProgressMonitor monitor)
            throws JavaModelException {
        List<Map<String, Object>> members = new ArrayList<>();

        // If the type has a compilation unit, parse it for complexity
        if (type.getCompilationUnit() != null) {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(type.getCompilationUnit());
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration node) {
                    // Only include methods declared directly in this type
                    if (node.getParent() instanceof org.eclipse.jdt.core.dom.TypeDeclaration td) {
                        if (td.resolveBinding() != null
                                && type.getFullyQualifiedName().equals(
                                        td.resolveBinding().getQualifiedName())) {
                            Map<String, Object> methodInfo = new HashMap<>();
                            methodInfo.put("name", node.getName().getIdentifier());
                            methodInfo.put("kind", "method");
                            methodInfo.put("line", ast.getLineNumber(node.getStartPosition()));

                            int startLine = ast.getLineNumber(node.getStartPosition());
                            int endLine = ast.getLineNumber(
                                    node.getStartPosition() + node.getLength());
                            methodInfo.put("loc", endLine - startLine + 1);

                            int[] complexity = {1};
                            node.accept(new ASTVisitor() {
                                @Override
                                public boolean visit(IfStatement n) { complexity[0]++; return true; }
                                @Override
                                public boolean visit(ForStatement n) { complexity[0]++; return true; }
                                @Override
                                public boolean visit(EnhancedForStatement n) { complexity[0]++; return true; }
                                @Override
                                public boolean visit(WhileStatement n) { complexity[0]++; return true; }
                                @Override
                                public boolean visit(DoStatement n) { complexity[0]++; return true; }
                                @Override
                                public boolean visit(SwitchCase n) {
                                    if (!n.isDefault()) complexity[0]++;
                                    return true;
                                }
                                @Override
                                public boolean visit(CatchClause n) { complexity[0]++; return true; }
                                @Override
                                public boolean visit(ConditionalExpression n) { complexity[0]++; return true; }
                            });
                            methodInfo.put("cyclomaticComplexity", complexity[0]);

                            members.add(methodInfo);
                        }
                    }
                    return false;
                }
            });
        } else {
            // Binary type - just list method names without complexity
            for (IMethod method : type.getMethods()) {
                Map<String, Object> methodInfo = new HashMap<>();
                methodInfo.put("name", method.getElementName());
                methodInfo.put("kind", "method");
                members.add(methodInfo);
            }
        }

        // Add fields
        for (IField field : type.getFields()) {
            Map<String, Object> fieldInfo = new HashMap<>();
            fieldInfo.put("name", field.getElementName());
            fieldInfo.put("kind", "field");
            fieldInfo.put("type", field.getTypeSignature());
            members.add(fieldInfo);
        }

        return members;
    }
}
