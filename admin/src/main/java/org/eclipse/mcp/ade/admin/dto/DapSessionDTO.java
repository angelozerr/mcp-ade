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
package org.eclipse.mcp.ade.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.mcp.ade.dap.session.DapSession;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DapSessionDTO(
        String sessionId,
        String serverId,
        String workspaceUri,
        String language,
        String sessionName,
        String state,
        String createdBy,
        String launchedBy,
        boolean debugMode,
        String createdAt,
        String launchedAt,
        Map<String, Object> launchConfiguration
) {

    public static DapSessionDTO fromSession(DapSession session) {
        return new DapSessionDTO(
                session.getSessionId(),
                session.getServerConfig().getServerId(),
                session.getWorkspace().getNormalizedUri(),
                session.getLanguage(),
                session.getSessionName(),
                session.getState().name(),
                session.getCreatedBy() != null ? session.getCreatedBy().toString() : null,
                session.getLaunchedBy() != null ? session.getLaunchedBy().toString() : null,
                session.isDebugMode(),
                session.getCreatedAt() != null ? session.getCreatedAt().toString() : null,
                session.getLaunchedAt() != null ? session.getLaunchedAt().toString() : null,
                session.getLaunchConfiguration()
        );
    }
}
