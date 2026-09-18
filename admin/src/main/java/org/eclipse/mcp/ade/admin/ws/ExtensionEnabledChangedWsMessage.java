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
package org.eclipse.mcp.ade.admin.ws;

public class ExtensionEnabledChangedWsMessage extends WsMessage {

    private final String extensionId;
    private final boolean enabled;

    public ExtensionEnabledChangedWsMessage(String extensionId, boolean enabled) {
        super(WsMessageType.EXTENSION_ENABLED_CHANGED);
        this.extensionId = extensionId;
        this.enabled = enabled;
    }

    public String getExtensionId() { return extensionId; }
    public boolean isEnabled() { return enabled; }
}
