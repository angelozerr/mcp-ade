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
 * Request parameters for file URI-based LSP requests.
 * Contains workspace root + file URI.
 */
public class FileUriRequestParams extends LspRequestParams {

    private final String fileUri;

    public FileUriRequestParams(String cwd, String fileUri) {
        super(cwd);
        this.fileUri = fileUri;
    }

    public String getFileUri() {
        return fileUri;
    }

    @Override
    public Map<String, Object> toArgumentsMap() {
        Map<String, Object> map = super.toArgumentsMap();
        map.put("uri", fileUri);
        return map;
    }
}

