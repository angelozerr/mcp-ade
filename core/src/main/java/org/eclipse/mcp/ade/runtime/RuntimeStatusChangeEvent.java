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
package org.eclipse.mcp.ade.runtime;

import org.eclipse.mcp.ade.installer.InstallationStatus;

/**
 * CDI event fired when a runtime installation status changes.
 */
public record RuntimeStatusChangeEvent(
    String runtimeId,
    InstallationStatus status,
    String error,
    String resolvedPath,
    String activeSource,
    boolean fallbackUsed,
    String sourcePreference
) {
}
