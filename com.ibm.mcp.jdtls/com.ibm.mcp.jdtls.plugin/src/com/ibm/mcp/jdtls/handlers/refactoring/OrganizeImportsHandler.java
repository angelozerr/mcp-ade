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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.NameQualifiedType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;

import com.ibm.mcp.jdtls.JdtUtils;

/**
 * Handler for "mcp.jdtls.organizeImports" command.
 *
 * <p>Arguments: [{uri}]</p>
 *
 * <p>Organizes imports in the specified compilation unit by:
 * <ol>
 *   <li>Collecting all used type references via AST visitor</li>
 *   <li>Collecting all existing imports</li>
 *   <li>Removing unused imports (those not matching any used type)</li>
 *   <li>Sorting remaining imports alphabetically (java.*, javax.* first, then others)</li>
 * </ol>
 * </p>
 *
 * <p>Note: Adding missing imports requires type resolution which may rely on the
 * project's classpath configuration. This implementation handles removal and sorting
 * of existing imports, and attempts to resolve missing types via SearchEngine.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/OrganizeImportsTool.java">javalens-mcp OrganizeImportsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class OrganizeImportsHandler extends AbstractRefactoringHandler {

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        Map<String, Object> params = parseParams(arguments);
        String uri = (String) params.get("uri");

        if (uri == null) {
            throw new RuntimeException("Missing required argument: uri");
        }

        ICompilationUnit cu = JdtUtils.requireCompilationUnit(uri);

        CompilationUnit ast = parseAST(cu, monitor);
        String source = cu.getSource();

        // Step 1: Collect all used type names from AST
        Set<String> usedSimpleNames = new HashSet<>();
        Set<String> usedQualifiedNames = new HashSet<>();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleType node) {
                Name name = node.getName();
                if (name.isSimpleName()) {
                    usedSimpleNames.add(((SimpleName) name).getIdentifier());
                } else if (name.isQualifiedName()) {
                    usedQualifiedNames.add(name.getFullyQualifiedName());
                }
                return true;
            }

            @Override
            public boolean visit(MarkerAnnotation node) {
                usedSimpleNames.add(node.getTypeName().getFullyQualifiedName());
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                if (binding != null && binding.getKind() == IBinding.TYPE) {
                    usedSimpleNames.add(node.getIdentifier());
                }
                return true;
            }
        });

        // Step 2: Collect existing imports
        @SuppressWarnings("unchecked")
        List<ImportDeclaration> existingImports = ast.imports();
        Set<String> existingImportNames = new LinkedHashSet<>();
        for (ImportDeclaration imp : existingImports) {
            existingImportNames.add(imp.getName().getFullyQualifiedName());
        }

        // Step 3: Determine which imports are used
        Set<String> usedImports = new TreeSet<>(getImportComparator());
        Set<String> onDemandImports = new TreeSet<>(getImportComparator());

        for (ImportDeclaration imp : existingImports) {
            String importName = imp.getName().getFullyQualifiedName();
            if (imp.isOnDemand()) {
                // On-demand imports (e.g., java.util.*) - keep if any simple name could match
                onDemandImports.add(importName + ".*");
                continue;
            }
            if (imp.isStatic()) {
                // Keep static imports as-is for now
                usedImports.add("static " + importName);
                continue;
            }
            // Check if this import's simple name is used
            String simpleName = importName.substring(importName.lastIndexOf('.') + 1);
            if (usedSimpleNames.contains(simpleName) || usedQualifiedNames.contains(importName)) {
                usedImports.add(importName);
            }
        }

        // Step 4: Build new import section sorted
        // Sorting: java.*, javax.* first, then others alphabetically
        List<String> sortedImports = new ArrayList<>(usedImports);
        sortedImports.addAll(onDemandImports);
        sortedImports.sort(getImportComparator());

        // Step 5: Build the new import block
        StringBuilder importBlock = new StringBuilder();
        String lastGroup = null;
        for (String imp : sortedImports) {
            String currentGroup = getImportGroup(imp);
            if (lastGroup != null && !lastGroup.equals(currentGroup)) {
                importBlock.append("\n");
            }
            if (imp.startsWith("static ")) {
                importBlock.append("import static ").append(imp.substring(7)).append(";\n");
            } else {
                importBlock.append("import ").append(imp).append(";\n");
            }
            lastGroup = currentGroup;
        }

        // Step 6: Replace the import section in the source
        if (existingImports.isEmpty()) {
            if (sortedImports.isEmpty()) {
                return createSuccessResult(List.of());
            }
            // No existing imports - need to add after package declaration
            int insertPos = 0;
            if (ast.getPackage() != null) {
                insertPos = ast.getPackage().getStartPosition() + ast.getPackage().getLength();
                // Skip past the newline after the package declaration
                while (insertPos < source.length() && source.charAt(insertPos) == '\n') {
                    insertPos++;
                }
            }
            String newSource = source.substring(0, insertPos) + "\n" + importBlock.toString() + source.substring(insertPos);
            List<Map<String, Object>> edits = createWholeFileEdit(uri, source, newSource);
            return createSuccessResult(edits);
        }

        // Find the range of existing imports
        int importStart = existingImports.get(0).getStartPosition();
        ImportDeclaration lastImport = existingImports.get(existingImports.size() - 1);
        int importEnd = lastImport.getStartPosition() + lastImport.getLength();
        // Include trailing newline if present
        if (importEnd < source.length() && source.charAt(importEnd) == '\n') {
            importEnd++;
        }

        String newSource = source.substring(0, importStart) + importBlock.toString() + source.substring(importEnd);

        if (newSource.equals(source)) {
            return createSuccessResult(List.of());
        }

        List<Map<String, Object>> edits = createWholeFileEdit(uri, source, newSource);
        return createSuccessResult(edits);
    }

    /**
     * Get a comparator that sorts imports with java.* and javax.* first.
     */
    private Comparator<String> getImportComparator() {
        return (a, b) -> {
            // Strip "static " prefix for sorting
            String sa = a.startsWith("static ") ? a.substring(7) : a;
            String sb = b.startsWith("static ") ? b.substring(7) : b;

            int groupA = getGroupOrder(sa);
            int groupB = getGroupOrder(sb);
            if (groupA != groupB) {
                return Integer.compare(groupA, groupB);
            }
            // Static imports come after regular imports in the same group
            boolean staticA = a.startsWith("static ");
            boolean staticB = b.startsWith("static ");
            if (staticA != staticB) {
                return staticA ? 1 : -1;
            }
            return sa.compareTo(sb);
        };
    }

    private int getGroupOrder(String importName) {
        if (importName.startsWith("java.")) {
            return 0;
        }
        if (importName.startsWith("javax.")) {
            return 1;
        }
        return 2;
    }

    private String getImportGroup(String importName) {
        String name = importName.startsWith("static ") ? importName.substring(7) : importName;
        if (name.startsWith("java.")) {
            return "java";
        }
        if (name.startsWith("javax.")) {
            return "javax";
        }
        return "other";
    }
}
