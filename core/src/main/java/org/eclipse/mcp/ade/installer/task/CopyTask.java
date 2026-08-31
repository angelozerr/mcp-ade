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
package org.eclipse.mcp.ade.installer.task;

import com.google.gson.JsonObject;
import org.eclipse.mcp.ade.installer.InstallerContext;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class CopyTask extends InstallerTask {
    private static final Logger LOG = Logger.getLogger(CopyTask.class);

    private final String source;
    private final String destination;

    public CopyTask(String name, InstallerTask onSuccess, String source, String destination) {
        super(name, onSuccess);
        this.source = source;
        this.destination = destination;
    }

    @Override
    protected boolean run(InstallerContext context) {
        String resolvedSource = context.resolveVariables(source);
        String resolvedDestination = context.resolveVariables(destination);

        context.traceInfo("Copying from: " + resolvedSource + " to: " + resolvedDestination);
        context.getProgress().reportProgress("Copying " + getName());

        try {
            Path destPath = Paths.get(resolvedDestination);
            Files.createDirectories(destPath.getParent());

            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            InputStream resourceStream = classLoader.getResourceAsStream(resolvedSource.startsWith("/") ? resolvedSource.substring(1) : resolvedSource);
            if (resourceStream == null) {
                throw new IOException("Resource not found: " + resolvedSource);
            }

            try (InputStream is = resourceStream) {
                Files.copy(is, destPath, StandardCopyOption.REPLACE_EXISTING);
            }

            context.getProgress().reportProgress(100, "Copy complete");
            context.traceInfo("Copied to: " + resolvedDestination);

            return true;

        } catch (IOException e) {
            LOG.errorf(e, "Copy failed: %s -> %s", resolvedSource, resolvedDestination);
            context.traceError("Copy failed: " + e.getMessage());
            throw new IllegalStateException("Copy '" + getName() + "' failed: " + e.getMessage(), e);
        }
    }

    public static class Factory extends InstallerTaskFactoryBase {
        @Override
        public String getType() {
            return "copy";
        }

        @Override
        protected String getDefaultName() {
            return "Copy";
        }

        @Override
        protected InstallerTask create(String name, InstallerTask onSuccess, JsonObject json) {
            String source = json.get("source").getAsString();
            String destination = json.get("destination").getAsString();
            return new CopyTask(name, onSuccess, source, destination);
        }
    }
}
