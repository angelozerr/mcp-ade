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
package com.ibm.mcp.languagetools.lsp.tools.params;

import java.util.Map;

/**
 * Request parameters for workspace/symbol LSP requests.
 * Contains workspace root + search query.
 */
public class WorkspaceSymbolRequestParams extends LspRequestParams {

    private final String query;

    public WorkspaceSymbolRequestParams(String cwd, String query) {
        super(cwd);
        this.query = query;
    }

    public String getQuery() {
        return query;
    }

    @Override
    public Map<String, Object> toArgumentsMap() {
        Map<String, Object> map = super.toArgumentsMap();
        map.put("query", query);
        return map;
    }
}
