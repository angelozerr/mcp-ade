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
package org.eclipse.mcp.ade.lsp.tools;

import org.eclipse.mcp.ade.language.LanguageRegistry;
import org.eclipse.mcp.ade.lsp.tools.params.FileUriRequestParams;
import org.eclipse.mcp.ade.lsp.tools.strategies.DiagnosticsStrategy;
import org.eclipse.mcp.ade.tools.ToolArgDescriptions;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.util.concurrent.CompletableFuture;


@ApplicationScoped
public class DiagnosticsTools {

    private static final Logger LOG = Logger.getLogger(DiagnosticsTools.class);

    @Inject
    LspRequestExecutor requestExecutor;

    @Inject
    LanguageRegistry languageRegistry;

    @Tool(
            name = "get_diagnostics",
            description = "Get diagnostics (errors, warnings) for a file from all language servers. " +
            "The workspace is auto-detected and initialized if needed. " +
            "Example: getDiagnostics(cwd='/home/user/projects/my-app', fileUri='file:///home/user/projects/my-app/src/Main.java')")
    public CompletableFuture<String> getDiagnostics(
            @ToolArg(description = ToolArgDescriptions.CWD) String cwd,
            @ToolArg(description = ToolArgDescriptions.URI) String uri,
            Cancellation cancellation,
            Progress progress) {
        FileUriRequestParams params = new FileUriRequestParams(cwd, uri);
        return requestExecutor.executeAsString(params, new DiagnosticsStrategy(languageRegistry), cancellation, progress);
    }


}
