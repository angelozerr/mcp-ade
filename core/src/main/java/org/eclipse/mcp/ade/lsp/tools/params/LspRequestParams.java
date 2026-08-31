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
package org.eclipse.mcp.ade.lsp.tools.params;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for all LSP request parameters.
 * Contains the workspace root (cwd).
 */
public class LspRequestParams {

    private final String cwd;

    public LspRequestParams(String cwd) {
        this.cwd = cwd;
    }

    public String getCwd() {
        return cwd;
    }

    public Map<String, Object> toArgumentsMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cwd", cwd);
        return map;
    }
}
