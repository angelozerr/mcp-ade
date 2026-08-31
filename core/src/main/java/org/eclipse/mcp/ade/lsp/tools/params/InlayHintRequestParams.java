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
 * Request parameters for inlay hint LSP requests.
 * Contains workspace root + file URI + range.
 */
public class InlayHintRequestParams extends FileUriRequestParams {

    private final int startLine;
    private final int startCharacter;
    private final int endLine;
    private final int endCharacter;

    public InlayHintRequestParams(String cwd, String fileUri,
                                    int startLine, int startCharacter, int endLine, int endCharacter) {
        super(cwd, fileUri);
        this.startLine = startLine;
        this.startCharacter = startCharacter;
        this.endLine = endLine;
        this.endCharacter = endCharacter;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getStartCharacter() {
        return startCharacter;
    }

    public int getEndLine() {
        return endLine;
    }

    public int getEndCharacter() {
        return endCharacter;
    }

    @Override
    public Map<String, Object> toArgumentsMap() {
        Map<String, Object> map = super.toArgumentsMap();
        map.put("startLine", startLine);
        map.put("startCharacter", startCharacter);
        map.put("endLine", endLine);
        map.put("endCharacter", endCharacter);
        return map;
    }
}
