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
package org.eclipse.mcp.jdtls.handlers.diagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;
import org.eclipse.text.edits.TextEdit;

/**
 * Handler for "mcp.jdtls.applyQuickFix" command.
 *
 * <p>Arguments: [{uri, fixId, line, character}]</p>
 *
 * <p>Applies a specific quick fix identified by fixId using ASTRewrite.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/ApplyQuickFixTool.java">javalens-mcp ApplyQuickFixTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class ApplyQuickFixHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");
        String fixId = (String) params.get("fixId");
        int line = ((Number) params.get("line")).intValue();
        int character = ((Number) params.get("character")).intValue();

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        int offset = JdtUtils.getOffset(cu, line, character);

        try {
            switch (fixId) {
                case "add_import":
                    return applyAddImport(cu, ast, offset, uri, monitor);
                case "remove_import":
                    return applyRemoveImport(cu, ast, offset, uri);
                case "add_throws":
                    return applyAddThrows(cu, ast, offset, uri);
                case "surround_try_catch":
                    return applySurroundTryCatch(cu, ast, offset, uri);
                case "add_override":
                    return applyAddOverride(cu, ast, offset, uri);
                default:
                    return Map.of("applied", false, "error", "Unknown fix ID: " + fixId);
            }
        } catch (Exception e) {
            return Map.of("applied", false, "error", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyAddImport(ICompilationUnit cu, CompilationUnit ast, int offset, String uri,
            IProgressMonitor monitor) throws Exception {
        // Find the unresolved type name at the offset
        ASTNode node = NodeFinder.perform(ast, offset, 0);
        if (!(node instanceof SimpleName)) {
            return Map.of("applied", false, "error", "No type name found at position");
        }
        String typeName = ((SimpleName) node).getIdentifier();

        // Search for matching types
        List<String> matchingTypes = new ArrayList<>();
        SearchEngine searchEngine = new SearchEngine();
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();
        searchEngine.searchAllTypeNames(
                null,
                SearchPattern.R_EXACT_MATCH,
                typeName.toCharArray(),
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
                IJavaSearchConstants.TYPE,
                scope,
                new TypeNameMatchRequestor() {
                    @Override
                    public void acceptTypeNameMatch(TypeNameMatch match) {
                        matchingTypes.add(match.getFullyQualifiedName());
                    }
                },
                IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
                monitor);

        if (matchingTypes.isEmpty()) {
            return Map.of("applied", false, "error", "No matching type found for: " + typeName);
        }

        // Use the first match
        String fqn = matchingTypes.get(0);
        AST astObj = ast.getAST();
        ASTRewrite rewrite = ASTRewrite.create(astObj);

        ImportDeclaration importDecl = astObj.newImportDeclaration();
        importDecl.setName(astObj.newName(fqn));
        ListRewrite listRewrite = rewrite.getListRewrite(ast, CompilationUnit.IMPORTS_PROPERTY);
        listRewrite.insertLast(importDecl, null);

        TextEdit edits = rewrite.rewriteAST();
        cu.applyTextEdit(edits, monitor);
        cu.save(monitor, true);

        List<Map<String, Object>> editList = new ArrayList<>();
        Map<String, Object> edit = new HashMap<>();
        edit.put("uri", uri);
        edit.put("newText", "import " + fqn + ";");
        editList.add(edit);

        Map<String, Object> result = new HashMap<>();
        result.put("applied", true);
        result.put("fixId", "add_import");
        result.put("edits", editList);
        return result;
    }

    private Map<String, Object> applyRemoveImport(ICompilationUnit cu, CompilationUnit ast, int offset, String uri)
            throws Exception {
        // Find the import at the problem line
        IProblem[] problems = ast.getProblems();
        ImportDeclaration targetImport = null;

        for (IProblem problem : problems) {
            if (problem.getID() == IProblem.UnusedImport) {
                int problemOffset = problem.getSourceStart();
                ASTNode node = NodeFinder.perform(ast, problemOffset, problem.getSourceEnd() - problemOffset);
                if (node != null) {
                    ASTNode parent = node;
                    while (parent != null && !(parent instanceof ImportDeclaration)) {
                        parent = parent.getParent();
                    }
                    if (parent instanceof ImportDeclaration) {
                        // Check if this import is near the requested offset
                        if (Math.abs(problemOffset - offset) < 200) {
                            targetImport = (ImportDeclaration) parent;
                            break;
                        }
                    }
                }
            }
        }

        if (targetImport == null) {
            return Map.of("applied", false, "error", "No unused import found near position");
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        rewrite.remove(targetImport, null);

        TextEdit edits = rewrite.rewriteAST();
        cu.applyTextEdit(edits, null);
        cu.save(null, true);

        Map<String, Object> result = new HashMap<>();
        result.put("applied", true);
        result.put("fixId", "remove_import");
        result.put("edits", List.of(Map.of("uri", uri, "newText", "")));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyAddThrows(ICompilationUnit cu, CompilationUnit ast, int offset, String uri)
            throws Exception {
        // Find the enclosing method declaration
        ASTNode node = NodeFinder.perform(ast, offset, 0);
        MethodDeclaration method = findEnclosingMethod(node);
        if (method == null) {
            return Map.of("applied", false, "error", "No enclosing method found");
        }

        // Find the unhandled exception type from problems
        String exceptionType = "Exception";
        for (IProblem problem : ast.getProblems()) {
            if (problem.getID() == IProblem.UnhandledException) {
                String[] args = problem.getArguments();
                if (args != null && args.length > 0) {
                    exceptionType = args[0];
                }
                break;
            }
        }

        AST astObj = ast.getAST();
        ASTRewrite rewrite = ASTRewrite.create(astObj);
        Name exceptionName = astObj.newName(exceptionType);
        ListRewrite throwsRewrite = rewrite.getListRewrite(method, MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY);
        throwsRewrite.insertLast(astObj.newSimpleType(exceptionName), null);

        TextEdit edits = rewrite.rewriteAST();
        cu.applyTextEdit(edits, null);
        cu.save(null, true);

        Map<String, Object> result = new HashMap<>();
        result.put("applied", true);
        result.put("fixId", "add_throws");
        result.put("edits", List.of(Map.of("uri", uri, "newText", "throws " + exceptionType)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applySurroundTryCatch(ICompilationUnit cu, CompilationUnit ast, int offset,
            String uri) throws Exception {
        ASTNode node = NodeFinder.perform(ast, offset, 0);

        // Find the enclosing statement
        Statement statement = null;
        ASTNode current = node;
        while (current != null) {
            if (current instanceof ExpressionStatement) {
                statement = (Statement) current;
                break;
            }
            if (current instanceof Statement && current.getParent() instanceof Block) {
                statement = (Statement) current;
                break;
            }
            current = current.getParent();
        }

        if (statement == null) {
            return Map.of("applied", false, "error", "No statement found to wrap");
        }

        AST astObj = ast.getAST();
        ASTRewrite rewrite = ASTRewrite.create(astObj);

        TryStatement tryStatement = astObj.newTryStatement();
        Block tryBody = astObj.newBlock();
        Statement movedStatement = (Statement) rewrite.createMoveTarget(statement);
        tryBody.statements().add(movedStatement);
        tryStatement.setBody(tryBody);

        CatchClause catchClause = astObj.newCatchClause();
        SingleVariableDeclaration exceptionDecl = astObj.newSingleVariableDeclaration();
        exceptionDecl.setType(astObj.newSimpleType(astObj.newSimpleName("Exception")));
        exceptionDecl.setName(astObj.newSimpleName("e"));
        catchClause.setException(exceptionDecl);
        Block catchBody = astObj.newBlock();
        catchClause.setBody(catchBody);
        tryStatement.catchClauses().add(catchClause);

        rewrite.replace(statement, tryStatement, null);

        TextEdit edits = rewrite.rewriteAST();
        cu.applyTextEdit(edits, null);
        cu.save(null, true);

        Map<String, Object> result = new HashMap<>();
        result.put("applied", true);
        result.put("fixId", "surround_try_catch");
        result.put("edits", List.of(Map.of("uri", uri, "newText", "try { ... } catch (Exception e) { }")));
        return result;
    }

    private Map<String, Object> applyAddOverride(ICompilationUnit cu, CompilationUnit ast, int offset, String uri)
            throws Exception {
        ASTNode node = NodeFinder.perform(ast, offset, 0);
        MethodDeclaration method = findEnclosingMethod(node);
        if (method == null) {
            return Map.of("applied", false, "error", "No method found at position");
        }

        AST astObj = ast.getAST();
        ASTRewrite rewrite = ASTRewrite.create(astObj);

        MarkerAnnotation annotation = astObj.newMarkerAnnotation();
        annotation.setTypeName(astObj.newSimpleName("Override"));
        ListRewrite modifiers = rewrite.getListRewrite(method, MethodDeclaration.MODIFIERS2_PROPERTY);
        modifiers.insertFirst(annotation, null);

        TextEdit edits = rewrite.rewriteAST();
        cu.applyTextEdit(edits, null);
        cu.save(null, true);

        Map<String, Object> result = new HashMap<>();
        result.put("applied", true);
        result.put("fixId", "add_override");
        result.put("edits", List.of(Map.of("uri", uri, "newText", "@Override")));
        return result;
    }

    private MethodDeclaration findEnclosingMethod(ASTNode node) {
        ASTNode current = node;
        while (current != null) {
            if (current instanceof MethodDeclaration) {
                return (MethodDeclaration) current;
            }
            current = current.getParent();
        }
        return null;
    }
}
