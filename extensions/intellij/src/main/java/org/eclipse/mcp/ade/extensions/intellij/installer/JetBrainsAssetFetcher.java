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
package org.eclipse.mcp.ade.extensions.intellij.installer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.mcp.ade.installer.download.ContentLengthAware;
import org.eclipse.mcp.ade.installer.download.DownloadUtils;
import org.eclipse.mcp.ade.installer.download.OpenVsxAssetFetcher;
import org.eclipse.mcp.ade.progress.NoOpProgressMonitor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fetches the IntelliJ Language Server download URL by:
 * <ol>
 *   <li>Resolving the VSIX URL from Open VSX (via the parent class)</li>
 *   <li>Downloading the VSIX and extracting {@code extension/server-bundle.json}</li>
 *   <li>Returning the server binary URL from the {@code url} field</li>
 * </ol>
 */
public class JetBrainsAssetFetcher extends OpenVsxAssetFetcher {

    private static final String SERVER_BUNDLE_ENTRY = "extension/server-bundle.json";

    public JetBrainsAssetFetcher(String namespace, String extensionName, boolean targetPlatform) {
        super(namespace, extensionName, targetPlatform);
    }

    @Override
    protected String resolveDownloadUrl(String vsixUrl, Reporter reporter) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("intellij-vsix-", ".vsix");
            reporter.setText("> Downloading VSIX to extract server bundle info...");

            VsixDownloadProgressMonitor progressMonitor = new VsixDownloadProgressMonitor(reporter);
            DownloadUtils.download(vsixUrl, tempFile, progressMonitor);

            String serverUrl = extractServerBundleUrl(tempFile);
            if (serverUrl == null) {
                reporter.setText("No server-bundle.json found in VSIX");
                return null;
            }
            reporter.setText("Server bundle resolved: " + serverUrl);
            return serverUrl;
        } catch (Exception e) {
            reporter.setText("Error extracting server bundle from VSIX", e);
            return null;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String extractServerBundleUrl(Path vsixFile) throws Exception {
        try (ZipFile zip = new ZipFile(vsixFile.toFile())) {
            ZipEntry entry = zip.getEntry(SERVER_BUNDLE_ENTRY);
            if (entry == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            JsonElement parsed = JsonParser.parseString(sb.toString());
            if (parsed.isJsonObject()) {
                JsonObject bundle = parsed.getAsJsonObject();
                if (bundle.has("url")) {
                    return bundle.get("url").getAsString();
                }
            }
            return null;
        }
    }

    private static class VsixDownloadProgressMonitor extends NoOpProgressMonitor implements ContentLengthAware {
        private final Reporter reporter;
        private long contentLength = -1;

        VsixDownloadProgressMonitor(Reporter reporter) {
            this.reporter = reporter;
        }

        @Override
        public void setContentLength(long contentLength) {
            this.contentLength = contentLength;
        }

        @Override
        public void reportProgress(double progress, String message) {
            if (contentLength > 0) {
                double fraction = progress / 100.0;
                long downloaded = (long) (fraction * contentLength);
                String downloadedMB = String.format("%.1f", downloaded / 1024.0 / 1024.0);
                String totalMB = String.format("%.1f", contentLength / 1024.0 / 1024.0);
                reporter.setUpdateText(String.format("Downloading VSIX: %s MB / %s MB (%.0f%%)",
                        downloadedMB, totalMB, progress));
            } else {
                reporter.setUpdateText(String.format("Downloading VSIX (%.0f%%)", progress));
            }
        }
    }
}
