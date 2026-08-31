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
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.mcp.jdtls.ICommandHandler;
import org.eclipse.mcp.jdtls.JdtUtils;
import org.eclipse.text.edits.TextEdit;

/**
 * Handler for "mcp.jdtls.applyCleanup" command.
 *
 * <p>Arguments: [{uri, cleanupId}]</p>
 *
 * <p>Applies a specified cleanup operation to a compilation unit. Supported
 * cleanupIds: remove_unused_imports, add_missing_override, convert_to_lambda,
 * add_missing_serial_version_id, remove_unnecessary_casts, add_final_modifier.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/ApplyCleanupTool.java">javalens-mcp ApplyCleanupTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class ApplyCleanupHandler implements ICommandHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        if (arguments == null || arguments.isEmpty()) {
            throw new RuntimeException("Missing arguments");
        }

        Map<String, Object> params = (Map<String, Object>) arguments.get(0);
        String uri = (String) params.get("uri");
        String cleanupId = (String) params.get("cleanupId");

        if (cleanupId == null) {
            throw new RuntimeException("Missing cleanupId");
        }

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(monitor);

        int changesApplied;
        List<Map<String, Object>> edits = new ArrayList<>();

        switch (cleanupId) {
            case "remove_unused_imports":
                changesApplied = removeUnusedImports(cu, ast, edits, uri, monitor);
                break;
            case "add_missing_override":
                changesApplied = addMissingOverride(cu, ast, edits, uri, monitor);
                break;
            case "convert_to_lambda":
                changesApplied = convertToLambda(cu, ast, edits, uri, monitor);
                break;
            case "add_missing_serial_version_id":
                changesApplied = addMissingSerialVersionId(cu, ast, edits, uri, monitor);
                break;
            case "remove_unnecessary_casts":
                changesApplied = removeUnnecessaryCasts(cu, ast, edits, uri, monitor);
                break;
            case "add_final_modifier":
                changesApplied = addFinalModifier(cu, ast, edits, uri, monitor);
                break;
            default:
                throw new RuntimeException("Unknown cleanupId: " + cleanupId);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("uri", uri);
        result.put("cleanupId", cleanupId);
        result.put("changesApplied", changesApplied);
        result.put("edits", edits);
        return result;
    }

    private int removeUnusedImports(ICompilationUnit cu, CompilationUnit ast, List<Map<String, Object>> edits,
            String uri, IProgressMonitor monitor) throws Exception {
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        int[] count = {0};

        for (Object obj : ast.imports()) {
            ImportDeclaration importDecl = (ImportDeclaration) obj;
            ITypeBinding binding = importDecl.resolveBinding() instanceof ITypeBinding
                    ? (ITypeBinding) importDecl.resolveBinding()
                    : null;
            // Check if the import is used by looking at the binding
            // A simple heuristic: check if any type in the CU references this import
            if (!isImportUsed(ast, importDecl)) {
                rewrite.remove(importDecl, null);
                edits.add(createEditInfo(uri, ast, importDecl, ""));
                count[0]++;
            }
        }

        if (count[0] > 0) {
            TextEdit textEdits = rewrite.rewriteAST();
            cu.applyTextEdit(textEdits, monitor);
            cu.save(monitor, true);
        }

        return count[0];
    }

    private boolean isImportUsed(CompilationUnit ast, ImportDeclaration importDecl) {
        String importName = importDecl.getName().getFullyQualifiedName();
        if (importDecl.isOnDemand()) {
            return true; // Cannot easily determine usage of wildcard imports
        }
        String simpleName = importName.contains(".")
                ? importName.substring(importName.lastIndexOf('.') + 1)
                : importName;
        boolean[] used = {false};
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.SimpleName node) {
                if (node.getIdentifier().equals(simpleName) && !(node.getParent() instanceof ImportDeclaration)) {
                    used[0] = true;
                }
                return !used[0];
            }
        });
        return used[0];
    }

    private int addMissingOverride(ICompilationUnit cu, CompilationUnit ast, List<Map<String, Object>> edits,
            String uri, IProgressMonitor monitor) throws Exception {
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        int[] count = {0};

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding binding = node.resolveBinding();
                if (binding == null) {
                    return true;
                }

                // Check if method overrides a superclass/interface method
                ITypeBinding declaringClass = binding.getDeclaringClass();
                if (declaringClass == null) {
                    return true;
                }

                boolean overrides = false;
                ITypeBinding superClass = declaringClass.getSuperclass();
                if (superClass != null) {
                    for (IMethodBinding method : superClass.getDeclaredMethods()) {
                        if (binding.overrides(method)) {
                            overrides = true;
                            break;
                        }
                    }
                }
                if (!overrides) {
                    for (ITypeBinding iface : declaringClass.getInterfaces()) {
                        for (IMethodBinding method : iface.getDeclaredMethods()) {
                            if (binding.overrides(method)) {
                                overrides = true;
                                break;
                            }
                        }
                        if (overrides) break;
                    }
                }

                if (overrides && !hasOverrideAnnotation(node)) {
                    MarkerAnnotation annotation = ast.getAST().newMarkerAnnotation();
                    annotation.setTypeName(ast.getAST().newSimpleName("Override"));
                    ListRewrite modifiers = rewrite.getListRewrite(node,
                            MethodDeclaration.MODIFIERS2_PROPERTY);
                    modifiers.insertFirst(annotation, null);
                    edits.add(createEditInfo(uri, ast, node, "@Override"));
                    count[0]++;
                }
                return true;
            }
        });

        if (count[0] > 0) {
            TextEdit textEdits = rewrite.rewriteAST();
            cu.applyTextEdit(textEdits, monitor);
            cu.save(monitor, true);
        }

        return count[0];
    }

    private boolean hasOverrideAnnotation(MethodDeclaration method) {
        for (Object modifier : method.modifiers()) {
            if (modifier instanceof MarkerAnnotation) {
                if ("Override".equals(((MarkerAnnotation) modifier).getTypeName().getFullyQualifiedName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private int convertToLambda(ICompilationUnit cu, CompilationUnit ast, List<Map<String, Object>> edits,
            String uri, IProgressMonitor monitor) throws Exception {
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        int[] count = {0};

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(ClassInstanceCreation node) {
                if (node.getAnonymousClassDeclaration() == null) {
                    return true;
                }

                ITypeBinding typeBinding = node.resolveTypeBinding();
                if (typeBinding == null) {
                    return true;
                }

                // Check if it's a functional interface (single abstract method)
                ITypeBinding[] interfaces = typeBinding.getInterfaces();
                ITypeBinding targetType = typeBinding.getSuperclass() != null
                        && typeBinding.getSuperclass().isInterface()
                                ? typeBinding.getSuperclass()
                                : (interfaces.length == 1 ? interfaces[0] : null);

                if (targetType == null || !targetType.getFunctionalInterfaceMethod().equals(null)) {
                    // Simplified check - if the anonymous class has exactly one method
                    if (node.getAnonymousClassDeclaration().bodyDeclarations().size() == 1) {
                        Object bodyDecl = node.getAnonymousClassDeclaration().bodyDeclarations().get(0);
                        if (bodyDecl instanceof MethodDeclaration) {
                            MethodDeclaration method = (MethodDeclaration) bodyDecl;
                            if (method.getBody() != null) {
                                // Create lambda expression
                                AST astObj = ast.getAST();
                                LambdaExpression lambda = astObj.newLambdaExpression();

                                // Copy parameters
                                for (Object param : method.parameters()) {
                                    SingleVariableDeclaration svd = (SingleVariableDeclaration) param;
                                    VariableDeclarationFragment fragment = astObj.newVariableDeclarationFragment();
                                    fragment.setName(astObj.newSimpleName(svd.getName().getIdentifier()));
                                    lambda.parameters().add(fragment);
                                }

                                lambda.setBody(rewrite.createCopyTarget(method.getBody()));
                                rewrite.replace(node, lambda, null);
                                edits.add(createEditInfo(uri, ast, node, "lambda"));
                                count[0]++;
                            }
                        }
                    }
                }
                return true;
            }
        });

        if (count[0] > 0) {
            TextEdit textEdits = rewrite.rewriteAST();
            cu.applyTextEdit(textEdits, monitor);
            cu.save(monitor, true);
        }

        return count[0];
    }

    private int addMissingSerialVersionId(ICompilationUnit cu, CompilationUnit ast, List<Map<String, Object>> edits,
            String uri, IProgressMonitor monitor) throws Exception {
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        int[] count = {0};

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                ITypeBinding binding = node.resolveBinding();
                if (binding == null) {
                    return true;
                }

                // Check if implements Serializable
                boolean isSerializable = false;
                for (ITypeBinding iface : binding.getInterfaces()) {
                    if ("java.io.Serializable".equals(iface.getQualifiedName())) {
                        isSerializable = true;
                        break;
                    }
                }

                if (!isSerializable) {
                    return true;
                }

                // Check if serialVersionUID already exists
                boolean hasSerialVersionUID = false;
                for (FieldDeclaration field : node.getFields()) {
                    for (Object frag : field.fragments()) {
                        VariableDeclarationFragment vdf = (VariableDeclarationFragment) frag;
                        if ("serialVersionUID".equals(vdf.getName().getIdentifier())) {
                            hasSerialVersionUID = true;
                            break;
                        }
                    }
                }

                if (!hasSerialVersionUID) {
                    AST astObj = ast.getAST();
                    VariableDeclarationFragment fragment = astObj.newVariableDeclarationFragment();
                    fragment.setName(astObj.newSimpleName("serialVersionUID"));
                    fragment.setInitializer(astObj.newNumberLiteral("1L"));
                    FieldDeclaration fieldDecl = astObj.newFieldDeclaration(fragment);
                    fieldDecl.setType(astObj.newPrimitiveType(org.eclipse.jdt.core.dom.PrimitiveType.LONG));
                    fieldDecl.modifiers().add(astObj.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD));
                    fieldDecl.modifiers().add(astObj.newModifier(Modifier.ModifierKeyword.STATIC_KEYWORD));
                    fieldDecl.modifiers().add(astObj.newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD));

                    ListRewrite bodyRewrite = rewrite.getListRewrite(node, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
                    bodyRewrite.insertFirst(fieldDecl, null);
                    edits.add(createEditInfo(uri, ast, node, "private static final long serialVersionUID = 1L;"));
                    count[0]++;
                }
                return true;
            }
        });

        if (count[0] > 0) {
            TextEdit textEdits = rewrite.rewriteAST();
            cu.applyTextEdit(textEdits, monitor);
            cu.save(monitor, true);
        }

        return count[0];
    }

    private int removeUnnecessaryCasts(ICompilationUnit cu, CompilationUnit ast, List<Map<String, Object>> edits,
            String uri, IProgressMonitor monitor) throws Exception {
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        int[] count = {0};

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(CastExpression node) {
                ITypeBinding castType = node.getType().resolveBinding();
                Expression expression = node.getExpression();
                ITypeBinding exprType = expression.resolveTypeBinding();

                if (castType != null && exprType != null) {
                    // Cast is unnecessary if expression type is the same as or a subtype of cast type
                    if (castType.isEqualTo(exprType) || exprType.isSubTypeCompatible(castType)) {
                        rewrite.replace(node, rewrite.createCopyTarget(expression), null);
                        edits.add(createEditInfo(uri, ast, node, "removed cast"));
                        count[0]++;
                    }
                }
                return true;
            }
        });

        if (count[0] > 0) {
            TextEdit textEdits = rewrite.rewriteAST();
            cu.applyTextEdit(textEdits, monitor);
            cu.save(monitor, true);
        }

        return count[0];
    }

    private int addFinalModifier(ICompilationUnit cu, CompilationUnit ast, List<Map<String, Object>> edits,
            String uri, IProgressMonitor monitor) throws Exception {
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        int[] count = {0};

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationStatement node) {
                // Check if variable is not already final
                int modifiers = node.getModifiers();
                if (Modifier.isFinal(modifiers)) {
                    return true;
                }

                // Check if the variable is never reassigned (simplified: single fragment, has initializer)
                if (node.fragments().size() == 1) {
                    VariableDeclarationFragment fragment = (VariableDeclarationFragment) node.fragments().get(0);
                    if (fragment.getInitializer() != null) {
                        ListRewrite modifierRewrite = rewrite.getListRewrite(node,
                                VariableDeclarationStatement.MODIFIERS2_PROPERTY);
                        modifierRewrite.insertFirst(
                                ast.getAST().newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD), null);
                        edits.add(createEditInfo(uri, ast, node, "final"));
                        count[0]++;
                    }
                }
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                // Add final to method parameters
                int modifiers = node.getModifiers();
                if (Modifier.isFinal(modifiers)) {
                    return true;
                }

                if (node.getParent() instanceof MethodDeclaration) {
                    ListRewrite modifierRewrite = rewrite.getListRewrite(node,
                            SingleVariableDeclaration.MODIFIERS2_PROPERTY);
                    modifierRewrite.insertFirst(
                            ast.getAST().newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD), null);
                    edits.add(createEditInfo(uri, ast, node, "final"));
                    count[0]++;
                }
                return true;
            }
        });

        if (count[0] > 0) {
            TextEdit textEdits = rewrite.rewriteAST();
            cu.applyTextEdit(textEdits, monitor);
            cu.save(monitor, true);
        }

        return count[0];
    }

    private Map<String, Object> createEditInfo(String uri, CompilationUnit ast, org.eclipse.jdt.core.dom.ASTNode node,
            String newText) {
        Map<String, Object> edit = new HashMap<>();
        edit.put("uri", uri);
        edit.put("line", ast.getLineNumber(node.getStartPosition()) - 1);
        edit.put("newText", newText);
        return edit;
    }
}
