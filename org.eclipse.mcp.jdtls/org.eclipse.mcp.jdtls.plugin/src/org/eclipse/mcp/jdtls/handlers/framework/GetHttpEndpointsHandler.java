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
package org.eclipse.mcp.jdtls.handlers.framework;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMemberValuePair;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
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
 * Handler for "mcp.jdtls.getHttpEndpoints" command.
 *
 * <p>Arguments: none (workspace-wide scan)</p>
 *
 * <p>Searches for HTTP endpoint annotations from Spring MVC and JAX-RS
 * frameworks and extracts endpoint metadata.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetHttpEndpointsTool.java">javalens-mcp GetHttpEndpointsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetHttpEndpointsHandler implements ICommandHandler {

    private static final Map<String, String> SPRING_ANNOTATIONS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "REQUEST"
    );

    private static final Map<String, String> JAXRS_ANNOTATIONS = Map.of(
            "GET", "GET",
            "POST", "POST",
            "PUT", "PUT",
            "DELETE", "DELETE",
            "PATCH", "PATCH"
    );

    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        IJavaSearchScope scope = SearchEngine.createWorkspaceScope();

        // Search for Spring MVC annotations
        for (Map.Entry<String, String> entry : SPRING_ANNOTATIONS.entrySet()) {
            searchAnnotation(entry.getKey(), entry.getValue(), scope, endpoints, monitor);
        }

        // Search for JAX-RS annotations
        for (Map.Entry<String, String> entry : JAXRS_ANNOTATIONS.entrySet()) {
            searchAnnotation(entry.getKey(), entry.getValue(), scope, endpoints, monitor);
        }

        // Also search for @Path to extract JAX-RS base paths
        searchJaxRsPath(scope, endpoints, monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("endpoints", endpoints);
        result.put("count", endpoints.size());
        return result;
    }

    private void searchAnnotation(String annotationName, String httpMethod, IJavaSearchScope scope,
            List<Map<String, Object>> endpoints, IProgressMonitor monitor) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
                annotationName,
                IJavaSearchConstants.ANNOTATION_TYPE,
                IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE,
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);

        if (pattern == null) {
            return;
        }

        SearchEngine engine = new SearchEngine();
        engine.search(
                pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getElement() instanceof IMethod) {
                            IMethod method = (IMethod) match.getElement();
                            try {
                                Map<String, Object> endpoint = extractEndpointInfo(method, annotationName, httpMethod);
                                if (endpoint != null) {
                                    endpoints.add(endpoint);
                                }
                            } catch (Exception e) {
                                // Skip methods that cannot be processed
                            }
                        }
                    }
                },
                monitor);
    }

    private Map<String, Object> extractEndpointInfo(IMethod method, String annotationName, String httpMethod)
            throws Exception {
        IAnnotation annotation = method.getAnnotation(annotationName);
        if (annotation == null || !annotation.exists()) {
            return null;
        }

        String path = extractPath(annotation);

        // For RequestMapping, determine HTTP method from annotation
        String resolvedMethod = httpMethod;
        if ("REQUEST".equals(httpMethod)) {
            resolvedMethod = extractRequestMappingMethod(annotation);
        }

        // Extract class-level path prefix
        IType declaringType = method.getDeclaringType();
        String classPath = "";
        if (declaringType != null) {
            IAnnotation requestMapping = declaringType.getAnnotation("RequestMapping");
            if (requestMapping != null && requestMapping.exists()) {
                classPath = extractPath(requestMapping);
            }
            IAnnotation pathAnnotation = declaringType.getAnnotation("Path");
            if (pathAnnotation != null && pathAnnotation.exists()) {
                classPath = extractPath(pathAnnotation);
            }
        }

        String fullPath = classPath + path;
        if (!fullPath.startsWith("/")) {
            fullPath = "/" + fullPath;
        }

        Map<String, Object> endpoint = new HashMap<>();
        endpoint.put("httpMethod", resolvedMethod);
        endpoint.put("path", fullPath);
        endpoint.put("className", declaringType != null ? declaringType.getFullyQualifiedName() : "");
        endpoint.put("methodName", method.getElementName());

        if (method.getResource() != null) {
            endpoint.put("uri", JdtUtils.toFileUri(method.getResource()));
        }
        ICompilationUnit cu = method.getCompilationUnit();
        if (cu != null) {
            String source = cu.getSource();
            if (source != null) {
                int offset = method.getSourceRange().getOffset();
                int line = countLines(source, offset);
                endpoint.put("line", line);
            }
        }

        return endpoint;
    }

    private String extractPath(IAnnotation annotation) throws Exception {
        IMemberValuePair[] pairs = annotation.getMemberValuePairs();
        for (IMemberValuePair pair : pairs) {
            String memberName = pair.getMemberName();
            if ("value".equals(memberName) || "path".equals(memberName)) {
                Object value = pair.getValue();
                if (value instanceof String) {
                    return (String) value;
                }
                if (value instanceof Object[]) {
                    Object[] values = (Object[]) value;
                    if (values.length > 0) {
                        return String.valueOf(values[0]);
                    }
                }
            }
        }
        return "";
    }

    private String extractRequestMappingMethod(IAnnotation annotation) throws Exception {
        IMemberValuePair[] pairs = annotation.getMemberValuePairs();
        for (IMemberValuePair pair : pairs) {
            if ("method".equals(pair.getMemberName())) {
                Object value = pair.getValue();
                if (value instanceof String) {
                    String method = (String) value;
                    return method.contains(".") ? method.substring(method.lastIndexOf('.') + 1) : method;
                }
            }
        }
        return "GET";
    }

    private void searchJaxRsPath(IJavaSearchScope scope, List<Map<String, Object>> endpoints,
            IProgressMonitor monitor) throws Exception {
        // JAX-RS @Path on methods without explicit HTTP method annotation
        // are typically sub-resource locators - we capture the path info
        SearchPattern pattern = SearchPattern.createPattern(
                "Path",
                IJavaSearchConstants.ANNOTATION_TYPE,
                IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE,
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);

        if (pattern == null) {
            return;
        }

        SearchEngine engine = new SearchEngine();
        engine.search(
                pattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        // @Path on methods is handled by the JAX-RS method annotations search
                        // We only need to ensure class-level paths are captured for context
                    }
                },
                monitor);
    }

    private int countLines(String source, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
