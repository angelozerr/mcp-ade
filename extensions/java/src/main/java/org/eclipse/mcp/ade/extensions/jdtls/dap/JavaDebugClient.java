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
package org.eclipse.mcp.ade.extensions.jdtls.dap;

import org.eclipse.mcp.ade.dap.client.DapClient;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.jboss.logging.Logger;

/**
 * Java-specific DAP client that handles Java Debug Server extensions to the DAP protocol.
 * Extends the base DapClient to support Java-specific notifications.
 */
public class JavaDebugClient extends DapClient {

    private static final Logger LOG = Logger.getLogger(JavaDebugClient.class);

    public JavaDebugClient() {
        super();
    }

    public JavaDebugClient(DapClient parentClient) {
        super(parentClient);
    }

    /**
     * Custom notification from Java Debug Server.
     * This is not part of the standard DAP specification.
     * The Java debugger sends this after launching a process to notify the client of the process ID.
     *
     * @param processId the process ID (can be Integer or Long depending on the debugger implementation)
     */
    @JsonNotification("processid")
    public void processId(Object processId) {
        LOG.debugf("Java Debug Server processId notification: %s", processId);
        // This is informational only - the Process event already provides systemProcessId
        // We just handle it to avoid "Unsupported notification method" warnings
    }
}
