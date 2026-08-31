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

public class RenameRequestParams extends FilePositionRequestParams {

    private final String newName;

    public RenameRequestParams(String cwd, String fileUri, int line, int character, String newName) {
        super(cwd, fileUri, line, character);
        this.newName = newName;
    }

    public String getNewName() {
        return newName;
    }

    @Override
    public Map<String, Object> toArgumentsMap() {
        Map<String, Object> map = super.toArgumentsMap();
        map.put("newName", newName);
        return map;
    }
}
