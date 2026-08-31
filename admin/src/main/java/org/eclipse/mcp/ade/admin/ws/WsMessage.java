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

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public abstract class WsMessage {

    private final WsMessageType type;

    protected WsMessage(WsMessageType type) {
        this.type = type;
    }

    public WsMessageType getType() {
        return type;
    }
}
