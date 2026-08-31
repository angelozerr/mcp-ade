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

import java.util.Map;

/**
 * Request parameters for document formatting LSP requests.
 * Contains workspace root + file URI + formatting options.
 */
public class FormattingRequestParams extends FileUriRequestParams {

    private final int tabSize;
    private final boolean insertSpaces;
    private final boolean apply;

    public FormattingRequestParams(String cwd, String fileUri, int tabSize, boolean insertSpaces, boolean apply) {
        super(cwd, fileUri);
        this.tabSize = tabSize;
        this.insertSpaces = insertSpaces;
        this.apply = apply;
    }

    public int getTabSize() {
        return tabSize;
    }

    public boolean isInsertSpaces() {
        return insertSpaces;
    }

    public boolean isApply() {
        return apply;
    }

    @Override
    public Map<String, Object> toArgumentsMap() {
        Map<String, Object> map = super.toArgumentsMap();
        map.put("tabSize", tabSize);
        map.put("insertSpaces", insertSpaces);
        map.put("apply", apply);
        return map;
    }
}
