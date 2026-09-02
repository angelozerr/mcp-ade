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
package org.eclipse.mcp.ade.dap.server.resolve;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Container for declarative resolve steps, keyed by request type.
 *
 * <p>Example server.json:</p>
 * <pre>{@code
 * "resolve": {
 *   "launch": [ ... steps ... ]
 * }
 * }</pre>
 */
public class ResolveConfig {

    private final Map<String, List<ResolveStepConfig>> steps;

    public ResolveConfig(Map<String, List<ResolveStepConfig>> steps) {
        this.steps = steps != null ? steps : Collections.emptyMap();
    }

    /**
     * Returns the resolve steps for the given request type ("launch" or "attach").
     */
    public List<ResolveStepConfig> getSteps(String requestType) {
        return steps.getOrDefault(requestType, Collections.emptyList());
    }

    /**
     * Returns true if there are resolve steps for the given request type.
     */
    public boolean hasSteps(String requestType) {
        List<ResolveStepConfig> list = steps.get(requestType);
        return list != null && !list.isEmpty();
    }
}
