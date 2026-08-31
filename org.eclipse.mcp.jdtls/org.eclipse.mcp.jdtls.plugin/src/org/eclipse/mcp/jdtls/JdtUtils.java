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
package org.eclipse.mcp.jdtls;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;

/**
 * Utility methods for resolving JDT elements from command arguments.
 */
public final class JdtUtils {

    private JdtUtils() {
    }

    /**
     * Resolve a type from command arguments.
     *
     * <p>Supports two argument formats:</p>
     * <ul>
     *   <li>[uri, line, character] - resolve type at position</li>
     *   <li>[fullyQualifiedName] - resolve by fully qualified name</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static IType resolveType(List<Object> arguments, IProgressMonitor monitor) throws JavaModelException {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }

        Object firstArg = arguments.get(0);

        if (firstArg instanceof Map) {
            Map<String, Object> params = (Map<String, Object>) firstArg;
            String uri = (String) params.get("uri");
            if (uri != null && params.containsKey("line") && params.containsKey("character")) {
                int line = ((Number) params.get("line")).intValue();
                int character = ((Number) params.get("character")).intValue();
                return resolveTypeAtPosition(uri, line, character, monitor);
            }
            String fqn = (String) params.get("fullyQualifiedName");
            if (fqn != null) {
                return resolveTypeByName(fqn);
            }
        }

        if (firstArg instanceof String) {
            return resolveTypeByName((String) firstArg);
        }

        return null;
    }

    /**
     * Resolve a type at a position in a file using codeSelect.
     */
    public static IType resolveTypeAtPosition(String uri, int line, int character, IProgressMonitor monitor)
            throws JavaModelException {
        ICompilationUnit cu = requireCompilationUnit(uri);
        int offset = getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);
        for (IJavaElement element : elements) {
            if (element instanceof IType type) {
                return type;
            }
        }
        for (IJavaElement element : elements) {
            IType enclosingType = (IType) element.getAncestor(IJavaElement.TYPE);
            if (enclosingType != null) {
                return enclosingType;
            }
        }
        return null;
    }

    /**
     * Resolve a Java element (type or method) from command arguments.
     *
     * <p>Same argument formats as {@link #resolveType}, but returns
     * the element directly instead of always coercing to IType.
     * For position-based input, returns the first IType or IMethod from codeSelect.</p>
     */
    @SuppressWarnings("unchecked")
    public static IJavaElement resolveElement(List<Object> arguments, IProgressMonitor monitor)
            throws JavaModelException {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }

        Object firstArg = arguments.get(0);

        if (firstArg instanceof Map) {
            Map<String, Object> params = (Map<String, Object>) firstArg;
            String uri = (String) params.get("uri");
            if (uri != null && params.containsKey("line") && params.containsKey("character")) {
                int line = ((Number) params.get("line")).intValue();
                int character = ((Number) params.get("character")).intValue();
                return resolveElementAtPosition(uri, line, character, monitor);
            }
            String fqn = (String) params.get("fullyQualifiedName");
            if (fqn != null) {
                return resolveTypeByName(fqn);
            }
        }

        if (firstArg instanceof String) {
            return resolveTypeByName((String) firstArg);
        }

        return null;
    }

    /**
     * Resolve a Java element at a position using codeSelect.
     * Returns the first IType or IMethod found.
     */
    public static IJavaElement resolveElementAtPosition(String uri, int line, int character,
                                                         IProgressMonitor monitor)
            throws JavaModelException {
        ICompilationUnit cu = requireCompilationUnit(uri);
        int offset = getOffset(cu, line, character);
        IJavaElement[] elements = cu.codeSelect(offset, 0);
        for (IJavaElement element : elements) {
            if (element instanceof IType || element instanceof IMethod) {
                return element;
            }
        }
        for (IJavaElement element : elements) {
            IType enclosingType = (IType) element.getAncestor(IJavaElement.TYPE);
            if (enclosingType != null) {
                return enclosingType;
            }
        }
        return null;
    }

    /**
     * Resolve a type by its fully qualified name across all Java projects.
     */
    public static IType resolveTypeByName(String fullyQualifiedName) throws JavaModelException {
        var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (var project : projects) {
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject != null && javaProject.exists()) {
                IType type = javaProject.findType(fullyQualifiedName);
                if (type != null && type.exists()) {
                    return type;
                }
            }
        }
        return null;
    }

    /**
     * Get the ICompilationUnit for a file URI, throwing if not found.
     */
    public static ICompilationUnit requireCompilationUnit(String uri) {
        ICompilationUnit cu = getCompilationUnit(uri);
        if (cu == null) {
            throw new RuntimeException("Compilation unit not found: " + uri);
        }
        return cu;
    }

    /**
     * Get the ICompilationUnit for a file URI.
     */
    public static ICompilationUnit getCompilationUnit(String uri) {
        URI fileUri = URI.create(uri);
        try {
            fileUri = new File(fileUri).toURI();
        } catch (Exception ignored) {
        }
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IFile[] files = root.findFilesForLocationURI(fileUri);
        if (files.length == 0) {
            files = refreshAndRetry(fileUri, root);
            if (files.length == 0) {
                return null;
            }
        }
        IJavaElement element = JavaCore.create(files[0]);
        if (element instanceof ICompilationUnit cu) {
            if (!cu.exists()) {
                files = refreshAndRetry(fileUri, root);
                if (files.length == 0) {
                    return null;
                }
                element = JavaCore.create(files[0]);
                if (element instanceof ICompilationUnit cu2 && cu2.exists()) {
                    return cu2;
                }
                return null;
            }
            return cu;
        }
        return null;
    }

    private static IFile[] refreshAndRetry(URI fileUri, IWorkspaceRoot root) {
        File file = new File(fileUri);
        if (!file.exists()) {
            return new IFile[0];
        }
        IPath filePath = org.eclipse.core.runtime.Path.fromOSString(file.getAbsolutePath());
        for (IProject project : root.getProjects()) {
            if (!project.isOpen()) {
                continue;
            }
            IPath projectLocation = project.getLocation();
            if (projectLocation != null && projectLocation.isPrefixOf(filePath)) {
                try {
                    project.refreshLocal(IResource.DEPTH_INFINITE, null);
                } catch (Exception e) {
                    // Best-effort refresh
                }
                return root.findFilesForLocationURI(fileUri);
            }
        }
        return new IFile[0];
    }

    /**
     * Convert line/character (0-based) to offset in a compilation unit.
     */
    public static int getOffset(ICompilationUnit cu, int line, int character) throws JavaModelException {
        String source = cu.getSource();
        if (source == null) {
            return 0;
        }
        int offset = 0;
        int currentLine = 0;
        for (int i = 0; i < source.length(); i++) {
            if (currentLine == line) {
                return offset + character;
            }
            if (source.charAt(i) == '\n') {
                currentLine++;
            }
            offset++;
        }
        return offset;
    }

    /**
     * Convert an offset in source to line/character (0-based) and put them
     * directly into the target map, replacing any "offset" key.
     */
    public static void putPosition(Map<String, Object> target, String source, int offset) {
        int line = 0;
        int character = 0;
        if (source != null) {
            for (int i = 0; i < Math.min(offset, source.length()); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                    character = 0;
                } else {
                    character++;
                }
            }
        }
        target.put("line", line);
        target.put("character", character);
    }

    /**
     * Get the source code content from a workspace resource (IFile → ICompilationUnit).
     */
    public static String getSource(IResource resource) {
        if (resource instanceof IFile file) {
            IJavaElement element = JavaCore.create(file);
            if (element instanceof ICompilationUnit cu) {
                try {
                    return cu.getSource();
                } catch (JavaModelException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Convert an {@link IResource} location URI to a normalized {@code file:///} URI string.
     *
     * <p>Java's {@code URI.toString()} produces {@code file:/C:/path} (single slash)
     * while LSP/MCP clients expect {@code file:///C:/path} (triple slash, RFC 8089).</p>
     */
    public static String toFileUri(IResource resource) {
        String uri = resource.getLocationURI().toString();
        if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
            uri = "file:///" + uri.substring(6);
        }
        return uri;
    }

    /**
     * Format a {@link SearchMatch} into a map with uri, line, character, element, and declaringType.
     */
    public static Map<String, Object> formatSearchMatch(SearchMatch match, Map<IResource, String> sourceCache) {
        Map<String, Object> entry = new HashMap<>();
        if (match.getResource() != null) {
            entry.put("uri", toFileUri(match.getResource()));
            String source = sourceCache.computeIfAbsent(match.getResource(), JdtUtils::getSource);
            putPosition(entry, source, match.getOffset());
        }
        if (match.getElement() instanceof IJavaElement element) {
            entry.put("element", element.getElementName());
            IType dt = (IType) element.getAncestor(IJavaElement.TYPE);
            if (dt != null) {
                entry.put("declaringType", dt.getFullyQualifiedName());
            }
        }
        return entry;
    }

    /**
     * Group a flat list of search results by file URI for compact output.
     *
     * <p>Transforms [{uri, line, character, ...}, ...] into
     * {uri1: [{line, character, ...}, ...], uri2: [...]}.
     * Removes the redundant "uri" field from each entry.</p>
     */
    public static Map<String, List<Map<String, Object>>> groupResultsByUri(List<Map<String, Object>> results) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> entry : results) {
            String uri = (String) entry.get("uri");
            if (uri == null) {
                uri = "unknown";
            }
            Map<String, Object> stripped = new LinkedHashMap<>(entry);
            stripped.remove("uri");
            grouped.computeIfAbsent(uri, k -> new ArrayList<>()).add(stripped);
        }
        return grouped;
    }

    /**
     * Resolve a search scope from command arguments.
     *
     * <p>If {@code scope=project} is specified, returns a SOURCES-only scope
     * for the given project (or the first Java project if unspecified).
     * Otherwise returns a workspace scope.</p>
     */
    @SuppressWarnings("unchecked")
    public static IJavaSearchScope resolveSearchScope(List<Object> arguments) {
        if (arguments != null && !arguments.isEmpty() && arguments.get(0) instanceof Map) {
            Map<String, Object> params = (Map<String, Object>) arguments.get(0);
            if (SearchScope.fromParams(params) == SearchScope.PROJECT) {
                String projectName = (String) params.get("projectName");
                IJavaProject javaProject = findJavaProject(projectName);
                if (javaProject != null) {
                    return SearchEngine.createJavaSearchScope(
                            new IJavaElement[]{javaProject}, IJavaSearchScope.SOURCES);
                }
            }
        }
        return SearchEngine.createWorkspaceScope();
    }

    /**
     * Find a Java project by name, or the first available Java project if name is null.
     */
    public static IJavaProject findJavaProject(String projectName) {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject project : projects) {
            try {
                if (project.isOpen() && project.hasNature(JavaCore.NATURE_ID)) {
                    if (projectName == null || projectName.equals(project.getName())) {
                        return JavaCore.create(project);
                    }
                }
            } catch (Exception e) {
                // Skip
            }
        }
        return null;
    }
}
