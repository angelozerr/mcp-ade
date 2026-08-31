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
 * Request parameters for file position-based LSP requests (references, definition, declaration, implementation, etc.).
 * Contains workspace root + file URI + line + character.
 */
public class FilePositionRequestParams extends FileUriRequestParams {

    private final int line;
    private final int character;

    public FilePositionRequestParams(String cwd, String fileUri, int line, int character) {
        super(cwd, fileUri);
        this.line = line;
        this.character = character;
    }

    public int getLine() {
        return line;
    }

    public int getCharacter() {
        return character;
    }

    @Override
    public Map<String, Object> toArgumentsMap() {
        Map<String, Object> map = super.toArgumentsMap();
        map.put("line", line);
        map.put("character", character);
        return map;
    }
}

