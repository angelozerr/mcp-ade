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
package org.eclipse.mcp.ade.variable;

import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.*;

/**
 * Central registry for variable resolution.
 * <p>
 * Loads {@link VariableResolver} implementations via CDI and {@link ServiceLoader},
 * and provides a single entry point for resolving variable expressions in template strings.
 * <p>
 * Non-CDI callers (e.g. {@code ServerBase}, {@code InstallerContext}) can use
 * {@link #getInstance()} to access the singleton.
 */
@Singleton
@Startup
public class VariableResolverRegistry {

    private static volatile VariableResolverRegistry instance;

    private final List<VariableResolver> resolvers = new ArrayList<>();

    @Inject
    Instance<VariableResolver> cdiResolvers;

    @PostConstruct
    void init() {
        instance = this;
        Set<String> seen = new HashSet<>();
        for (VariableResolver resolver : cdiResolvers) {
            resolvers.add(resolver);
            seen.add(resolver.getClass().getName());
        }
        ServiceLoader.load(VariableResolver.class).forEach(resolver -> {
            if (seen.add(resolver.getClass().getName())) {
                resolvers.add(resolver);
            }
        });
    }

    /**
     * Returns the singleton instance for non-CDI callers.
     * If CDI has not yet initialized the bean, creates a fallback instance
     * with resolvers loaded via {@link ServiceLoader}.
     */
    public static VariableResolverRegistry getInstance() {
        VariableResolverRegistry reg = instance;
        if (reg == null) {
            synchronized (VariableResolverRegistry.class) {
                reg = instance;
                if (reg == null) {
                    reg = new VariableResolverRegistry();
                    ServiceLoader.load(VariableResolver.class).forEach(reg.resolvers::add);
                    instance = reg;
                }
            }
        }
        return reg;
    }

    /**
     * Resolve all variable expressions in the input string.
     *
     * @param input the template string
     * @param context the resolution context
     * @return the resolved string, or the original input if no variables found
     */
    public String resolve(String input, VariableContext context) {
        if (input == null) {
            return null;
        }
        List<VariableExpression> expressions = VariableParser.parse(input);
        if (expressions.isEmpty()) {
            return input;
        }

        StringBuilder result = new StringBuilder(input);
        for (int i = expressions.size() - 1; i >= 0; i--) {
            VariableExpression expr = expressions.get(i);
            String value = resolveExpression(expr, context);
            if (value != null) {
                result.replace(expr.start(), expr.end(), value);
            }
        }
        return result.toString();
    }

    /**
     * Resolve variable expressions in all string values of a map.
     * Handles String values and List of String values (used by DAP launch configs).
     *
     * @param map the configuration map
     * @param context the resolution context
     * @return a new map with resolved values
     */
    public Map<String, Object> resolve(Map<String, Object> map, VariableContext context) {
        if (map == null) {
            return null;
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String str) {
                resolved.put(entry.getKey(), resolve(str, context));
            } else if (value instanceof List<?> list) {
                List<Object> resolvedList = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof String str) {
                        resolvedList.add(resolve(str, context));
                    } else {
                        resolvedList.add(item);
                    }
                }
                resolved.put(entry.getKey(), resolvedList);
            } else {
                resolved.put(entry.getKey(), value);
            }
        }
        return resolved;
    }

    /**
     * Programmatically add a resolver (for testing or non-CDI usage).
     */
    public void addResolver(VariableResolver resolver) {
        resolvers.add(resolver);
    }

    private String resolveExpression(VariableExpression expression, VariableContext context) {
        for (VariableResolver resolver : resolvers) {
            String value = resolver.resolve(expression, context);
            if (value != null) {
                return value;
            }
        }
        if (expression.prefix() == null) {
            return context.getExtraVariable(expression.name());
        }
        return null;
    }
}
