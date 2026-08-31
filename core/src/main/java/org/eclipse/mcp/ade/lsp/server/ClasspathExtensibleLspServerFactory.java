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
package org.eclipse.mcp.ade.lsp.server;

import org.eclipse.mcp.ade.workspace.Workspace;
import org.jboss.logging.Logger;

/**
 * Factory for creating ClasspathExtensibleLspServer instances.
 * Handles servers that declare "acceptContributions": ["classpath"] in their server.json.
 */
public class ClasspathExtensibleLspServerFactory implements LspServerFactory {

    private static final Logger LOG = Logger.getLogger(ClasspathExtensibleLspServerFactory.class);

    @Override
    public boolean canHandle(LspServerConfig config, Workspace workspace) {
        if (config == null || config.isContributionOnly()) {
            return false;
        }
        return config.acceptsContribution(ClasspathExtensibleContributes.CLASSPATH);
    }

    @Override
    public LspServer createServer(LspServerCreateParams params) {
        LOG.infof("Creating classpath-extensible LSP server for %s", params.getConfig().getServerId());
        return new ClasspathExtensibleLspServer(params.getConfig(), params.getWorkspace());
    }
}
