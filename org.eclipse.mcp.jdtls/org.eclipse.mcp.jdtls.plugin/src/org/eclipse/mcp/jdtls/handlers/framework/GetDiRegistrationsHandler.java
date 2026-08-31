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

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
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
 * Handler for "mcp.jdtls.getDiRegistrations" command.
 *
 * <p>Arguments: optional [{scope, projectName}]</p>
 * <ul>
 *   <li>{@code scope} - "project" to restrict to project sources only (faster),
 *       or "workspace" (default) for full workspace scan</li>
 *   <li>{@code projectName} - when scope is "project", specifies which project
 *       (defaults to first Java project)</li>
 * </ul>
 *
 * <p>Searches for dependency injection annotations from Spring and Jakarta CDI
 * frameworks and extracts component registration and injection point metadata.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetDiRegistrationsTool.java">javalens-mcp GetDiRegistrationsTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetDiRegistrationsHandler implements ICommandHandler {

    /** Spring stereotype annotations mapped to their type. */
    private static final Map<String, String> SPRING_STEREOTYPES = Map.of(
            "Component", "component",
            "Service", "service",
            "Repository", "repository",
            "Controller", "controller",
            "RestController", "controller",
            "Configuration", "configuration"
    );

    /** Jakarta CDI scope annotations mapped to scope names. */
    private static final Map<String, String> CDI_SCOPES = Map.of(
            "ApplicationScoped", "application",
            "RequestScoped", "request",
            "SessionScoped", "session",
            "Dependent", "dependent"
    );

    @SuppressWarnings("unchecked")
    @Override
    public Object execute(List<Object> arguments, IProgressMonitor monitor) throws Exception {
        List<Map<String, Object>> registrations = new ArrayList<>();
        List<Map<String, Object>> injectionPoints = new ArrayList<>();
        IJavaSearchScope scope = JdtUtils.resolveSearchScope(arguments);

        // Search for Spring stereotype annotations
        for (Map.Entry<String, String> entry : SPRING_STEREOTYPES.entrySet()) {
            searchStereotype(entry.getKey(), entry.getValue(), "singleton", scope, registrations, monitor);
        }

        // Search for @Bean methods in @Configuration classes
        searchBeanMethods(scope, registrations, monitor);

        // Search for Jakarta CDI scope annotations
        for (Map.Entry<String, String> entry : CDI_SCOPES.entrySet()) {
            searchStereotype(entry.getKey(), "component", entry.getValue(), scope, registrations, monitor);
        }

        // Search for @Named CDI beans
        searchStereotype("Named", "component", "dependent", scope, registrations, monitor);

        // Search for injection points (@Autowired and @Inject)
        searchInjectionPoints("Autowired", scope, injectionPoints, monitor);
        searchInjectionPoints("Inject", scope, injectionPoints, monitor);

        Map<String, Object> counts = new HashMap<>();
        counts.put("registrations", registrations.size());
        counts.put("injectionPoints", injectionPoints.size());

        Map<String, Object> result = new HashMap<>();
        result.put("registrations", registrations);
        result.put("injectionPoints", injectionPoints);
        result.put("counts", counts);
        return result;
    }

    private void searchStereotype(String annotationName, String stereotype, String defaultScope,
            IJavaSearchScope scope, List<Map<String, Object>> registrations,
            IProgressMonitor monitor) throws Exception {
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
                        if (match.getElement() instanceof IType) {
                            IType type = (IType) match.getElement();
                            try {
                                Map<String, Object> reg = new HashMap<>();
                                reg.put("className", type.getFullyQualifiedName());
                                reg.put("stereotype", stereotype);
                                reg.put("scope", resolveScope(type, defaultScope));
                                reg.put("annotationName", annotationName);

                                if (type.getResource() != null) {
                                    reg.put("uri", JdtUtils.toFileUri(type.getResource()));
                                }

                                ICompilationUnit cu = type.getCompilationUnit();
                                if (cu != null && type.getSourceRange() != null) {
                                    String source = cu.getSource();
                                    if (source != null) {
                                        int offset = type.getSourceRange().getOffset();
                                        reg.put("line", countLines(source, offset));
                                    }
                                }

                                registrations.add(reg);
                            } catch (Exception e) {
                                // Skip types that cannot be processed
                            }
                        }
                    }
                },
                monitor);
    }

    private String resolveScope(IType type, String defaultScope) throws Exception {
        // Check for Spring @Scope annotation
        IAnnotation scopeAnnotation = type.getAnnotation("Scope");
        if (scopeAnnotation != null && scopeAnnotation.exists()) {
            var pairs = scopeAnnotation.getMemberValuePairs();
            for (var pair : pairs) {
                if ("value".equals(pair.getMemberName())) {
                    return String.valueOf(pair.getValue());
                }
            }
        }

        // Check for CDI scope annotations
        for (Map.Entry<String, String> entry : CDI_SCOPES.entrySet()) {
            IAnnotation annotation = type.getAnnotation(entry.getKey());
            if (annotation != null && annotation.exists()) {
                return entry.getValue();
            }
        }

        return defaultScope;
    }

    private void searchBeanMethods(IJavaSearchScope scope, List<Map<String, Object>> registrations,
            IProgressMonitor monitor) throws Exception {
        SearchPattern pattern = SearchPattern.createPattern(
                "Bean",
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
                                IType declaringType = method.getDeclaringType();
                                Map<String, Object> reg = new HashMap<>();
                                reg.put("className", declaringType != null
                                        ? declaringType.getFullyQualifiedName() + "." + method.getElementName()
                                        : method.getElementName());
                                reg.put("stereotype", "bean");
                                reg.put("scope", "singleton");
                                reg.put("annotationName", "Bean");
                                reg.put("producerMethod", method.getElementName());

                                if (method.getResource() != null) {
                                    reg.put("uri", JdtUtils.toFileUri(method.getResource()));
                                }

                                ICompilationUnit cu = method.getCompilationUnit();
                                if (cu != null && method.getSourceRange() != null) {
                                    String source = cu.getSource();
                                    if (source != null) {
                                        int offset = method.getSourceRange().getOffset();
                                        reg.put("line", countLines(source, offset));
                                    }
                                }

                                registrations.add(reg);
                            } catch (Exception e) {
                                // Skip methods that cannot be processed
                            }
                        }
                    }
                },
                monitor);

        // Also search for @Produces (Jakarta CDI)
        SearchPattern producesPattern = SearchPattern.createPattern(
                "Produces",
                IJavaSearchConstants.ANNOTATION_TYPE,
                IJavaSearchConstants.ANNOTATION_TYPE_REFERENCE,
                SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE);

        if (producesPattern == null) {
            return;
        }

        engine.search(
                producesPattern,
                new SearchParticipant[]{SearchEngine.getDefaultSearchParticipant()},
                scope,
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getElement() instanceof IMethod) {
                            IMethod method = (IMethod) match.getElement();
                            try {
                                IType declaringType = method.getDeclaringType();
                                Map<String, Object> reg = new HashMap<>();
                                reg.put("className", declaringType != null
                                        ? declaringType.getFullyQualifiedName() + "." + method.getElementName()
                                        : method.getElementName());
                                reg.put("stereotype", "producer");
                                reg.put("scope", "dependent");
                                reg.put("annotationName", "Produces");
                                reg.put("producerMethod", method.getElementName());

                                if (method.getResource() != null) {
                                    reg.put("uri", JdtUtils.toFileUri(method.getResource()));
                                }

                                registrations.add(reg);
                            } catch (Exception e) {
                                // Skip methods that cannot be processed
                            }
                        }
                    }
                },
                monitor);
    }

    private void searchInjectionPoints(String annotationName, IJavaSearchScope scope,
            List<Map<String, Object>> injectionPoints, IProgressMonitor monitor) throws Exception {
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
                        Object element = match.getElement();
                        try {
                            Map<String, Object> injection = new HashMap<>();
                            injection.put("annotationName", annotationName);

                            if (element instanceof IField) {
                                IField field = (IField) element;
                                injection.put("kind", "field");
                                injection.put("name", field.getElementName());
                                injection.put("type", field.getTypeSignature());
                                IType declaringType = field.getDeclaringType();
                                if (declaringType != null) {
                                    injection.put("declaringClass", declaringType.getFullyQualifiedName());
                                }
                                if (field.getResource() != null) {
                                    injection.put("uri", JdtUtils.toFileUri(field.getResource()));
                                }
                            } else if (element instanceof IMethod) {
                                IMethod method = (IMethod) element;
                                injection.put("kind", method.isConstructor() ? "constructor" : "method");
                                injection.put("name", method.getElementName());
                                IType declaringType = method.getDeclaringType();
                                if (declaringType != null) {
                                    injection.put("declaringClass", declaringType.getFullyQualifiedName());
                                }
                                if (method.getResource() != null) {
                                    injection.put("uri", JdtUtils.toFileUri(method.getResource()));
                                }
                            } else {
                                return;
                            }

                            injectionPoints.add(injection);
                        } catch (Exception e) {
                            // Skip elements that cannot be processed
                        }
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
