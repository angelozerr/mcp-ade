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
package com.ibm.mcp.languagetools.lsp.server;

import com.ibm.mcp.languagetools.ContributionManager;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import com.ibm.mcp.languagetools.trace.TraceCollector;
import com.ibm.mcp.languagetools.utils.OSUtils;
import com.ibm.mcp.languagetools.workspace.Workspace;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Generic LSP server that supports classpath extensions.
 * Used for servers like MicroProfile LS, Lemminx, etc. that can be extended by adding JARs to their classpath.
 *
 * Any server can receive contributions with the format:
 * {
 *   "classpath": ["./server/extension.jar"],
 *   "documentSelector": [{"language": "xml"}]
 * }
 */
public class ClasspathExtensibleLspServer extends LspServer {

    private static final Logger LOG = Logger.getLogger(ClasspathExtensibleLspServer.class);
    private static final String CLASSPATH_SEPARATOR = OSUtils.isWindows() ? ";" : ":";

    public ClasspathExtensibleLspServer(LspServerConfig config, Workspace workspace) {
        super(config, workspace);
    }

    @Override
    protected CompletableFuture<Void> ensureContributorsInstalled(ProgressMonitor progressMonitor) {
        LOG.infof("ensureContributorsInstalled() called for %s", getId());
        ContributionManager.ContributionResult result = getWorkspace()
                .getApplication()
                .getContributionManager()
                .extractFilesFromContributionWithStatus(getId(), ClasspathExtensibleContributes.CLASSPATH);

        List<ServerConfigBase> contributors = result.getUninstalledContributors();
        if (contributors.isEmpty()) {
            LOG.infof("No uninstalled contributors found for %s", getId());
            return CompletableFuture.completedFuture(null);
        }

        LOG.infof("Installing %d contributor(s) for %s", contributors.size(), getId());
        var application = getWorkspace().getApplication();
        var pathManager = application.getPathManager();
        var traceCollector = getConfig().getTraceCollector();
        if (traceCollector != null && traceCollector.isEnabled()) {
            traceCollector.addTrace(getId(),
                    String.format("Installing %d contributor(s)...", contributors.size()),
                    TraceCollector.MessageType.INFO);
        }
        CompletableFuture<?>[] futures = contributors.stream()
                .map(contributor -> {
                    LOG.infof("Installing contributor '%s' classpath extensions for %s",
                            contributor.getServerId(), getId());
                    if (contributor.isContributionOnly() && contributor.getTraceCollector() == null && traceCollector != null) {
                        contributor.setTraceCollector(traceCollector);
                    }
                    return contributor.ensureInstalled(getWorkspace(), null, progressMonitor)
                            .exceptionally(error -> {
                                LOG.warnf(error, "Failed to install contributor '%s' for %s, continuing without it",
                                        contributor.getServerId(), getId());
                                return null;
                            });
                })
                .toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(futures)
                .thenRun(() -> {
                    LOG.infof("All %d contributor(s) installed for %s", contributors.size(), getId());
                    if (traceCollector != null && traceCollector.isEnabled()) {
                        traceCollector.addTrace(getId(),
                                String.format("All %d contributor(s) installed.", contributors.size()),
                                TraceCollector.MessageType.INFO);
                    }
                });
    }

    /**
     * Build command with classpath contributions added.
     * Expects the base command to use -jar or -cp, and injects extensions.
     */
    @Override
    protected List<String> buildCommand() throws IOException {
        var config = super.getConfig();
        LOG.infof("ClasspathExtensibleLspServer.buildCommand() called for %s", config.getServerId());

        // Get base command from config
        List<String> baseCommand = super.buildCommand();
        LOG.infof("Base command for %s: %s", config.getServerId(), String.join(" ", baseCommand));

        // Collect classpath contributions for this server
        List<String> classpathExtensions = collectClasspathExtensions();
        LOG.infof("Collected %d classpath extensions for %s", classpathExtensions.size(), config.getServerId());

        if (classpathExtensions.isEmpty()) {
            // No extensions, return base command as-is
            LOG.infof("No classpath extensions, returning base command for %s", config.getServerId());
            return baseCommand;
        }

        // Inject extensions into classpath
        LOG.infof("Injecting %d extensions into %s classpath", classpathExtensions.size(), config.getServerId());
        return injectClasspathExtensions(baseCommand, classpathExtensions);
    }

    /**
     * Collect all classpath contributions to this server.
     */
    private List<String> collectClasspathExtensions() {
        return getWorkspace()
                .getApplication()
                .getContributionManager().extractFilesFromContribution(getId(), "classpath");
    }

    /**
     * Inject jarExtensions into the command's classpath.
     * Handles both -jar and -cp styles.
     */
    private List<String> injectClasspathExtensions(List<String> baseCommand, List<String> extensions) {
        List<String> newCommand = new ArrayList<>();
        String separator = CLASSPATH_SEPARATOR;

        boolean foundClasspath = false;

        for (int i = 0; i < baseCommand.size(); i++) {
            String arg = baseCommand.get(i);

            // Case 1: -cp or -classpath
            if (arg.equals("-cp") || arg.equals("-classpath")) {
                newCommand.add(arg);
                i++;
                if (i < baseCommand.size()) {
                    // Append extensions to existing classpath
                    String existingCp = baseCommand.get(i);
                    String extensionsCp = buildExtensionsClasspath(extensions, separator);
                    newCommand.add(existingCp + separator + extensionsCp);
                    foundClasspath = true;
                }
                continue;
            }

            // Case 2: -jar (convert to -cp)
            if (arg.equals("-jar") && i + 1 < baseCommand.size()) {
                String jarPath = baseCommand.get(i + 1);
                String extensionsCp = buildExtensionsClasspath(extensions, separator);

                newCommand.add("-cp");
                newCommand.add(jarPath + separator + extensionsCp);
                i++; // Skip the jar path
                foundClasspath = true;
                continue;
            }

            newCommand.add(arg);
        }

        var config = super.getConfig();
        if (!foundClasspath) {
            LOG.warnf("Could not inject classpath contributions into %s command (no -cp or -jar found)", config.getServerId());
            return baseCommand;
        }

        LOG.infof("Injected %d classpath entries into %s", extensions.size(), config.getServerId());
        return newCommand;
    }

    /**
     * Build classpath string from extension paths.
     */
    private String buildExtensionsClasspath(List<String> extensions, String separator) {
        StringBuilder cp = new StringBuilder();
        for (int i = 0; i < extensions.size(); i++) {
            if (i > 0) {
                cp.append(separator);
            }
            cp.append(extensions.get(i).toString());
        }
        return cp.toString();
    }

    /**
     * Delegate to config's parent detection.
     * For contribution-only configs, this returns the server they contribute to.
     * For normal servers, this returns null.
     */
    public String getParentServerId() {
        return super.getConfig().getParentServerId();
    }

}
