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
import org.eclipse.mcp.ade.installer.download.DecompressorUtils;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Installer task that extracts a bundled archive (ZIP, tar.gz) from classpath resources
 * to a destination directory.
 */
public class ExtractResourceTask extends InstallerTask {
    private static final Logger LOG = Logger.getLogger(ExtractResourceTask.class);

    private final String source;
    private final String destination;

    public ExtractResourceTask(String name, InstallerTask onSuccess, String source, String destination) {
        super(name, onSuccess);
        this.source = source;
        this.destination = destination;
    }

    @Override
    protected boolean run(InstallerContext context) {
        String resolvedSource = context.resolveVariables(source);
        String resolvedDestination = context.resolveVariables(destination);

        context.traceInfo("Extracting resource: " + resolvedSource + " to: " + resolvedDestination);
        context.getProgress().reportProgress("Extracting " + getName());

        try {
            Path destPath = Paths.get(resolvedDestination);
            Files.createDirectories(destPath);

            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            String resourceName = resolvedSource.startsWith("/") ? resolvedSource.substring(1) : resolvedSource;
            InputStream resourceStream = classLoader.getResourceAsStream(resourceName);
            if (resourceStream == null) {
                throw new IOException("Resource not found: " + resolvedSource);
            }

            String fileName = resolvedSource.contains("/")
                    ? resolvedSource.substring(resolvedSource.lastIndexOf('/') + 1)
                    : resolvedSource;

            Path tempFile = Files.createTempFile("extract-resource-", "-" + fileName);
            try (InputStream is = resourceStream) {
                Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            try {
                DecompressorUtils.Decompressor decompressor = DecompressorUtils.getDecompressor(tempFile);
                if (decompressor == null) {
                    throw new IOException("Unsupported archive format: " + fileName);
                }
                decompressor.decompress(tempFile, destPath, context.getProgress());
            } finally {
                Files.deleteIfExists(tempFile);
            }

            context.getProgress().reportProgress(100, "Extraction complete");
            context.traceInfo("Extracted to: " + resolvedDestination);

            return true;

        } catch (IOException e) {
            LOG.errorf(e, "Extract failed: %s -> %s", resolvedSource, resolvedDestination);
            context.traceError("Extract failed: " + e.getMessage());
            throw new IllegalStateException("Extract '" + getName() + "' failed: " + e.getMessage(), e);
        }
    }

    public static class Factory extends InstallerTaskFactoryBase {
        @Override
        public String getType() {
            return "extractResource";
        }

        @Override
        protected String getDefaultName() {
            return "Extract Resource";
        }

        @Override
        protected InstallerTask create(String name, InstallerTask onSuccess, InstallerTask onFail, JsonObject json) {
            String source = json.get("source").getAsString();
            String destination = json.get("destination").getAsString();
            return new ExtractResourceTask(name, onSuccess, source, destination);
        }
    }
}
