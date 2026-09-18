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
package org.eclipse.mcp.ade.command;

import java.util.List;

/**
 * Metadata for a registered CLI command, used for discovery and help generation.
 */
public record CommandInfo(
        String domain,
        String action,
        String description,
        List<ArgInfo> args) {

    public record ArgInfo(
            String name,
            String description,
            boolean required,
            String defaultValue) {
    }
}
