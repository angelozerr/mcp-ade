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
package com.ibm.mcp.languagetools.installer.download;

import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for downloading files with progress tracking.
 * Inspired by lsp4ij's DownloadUtils.
 */
public class DownloadUtils {
    private static final Logger LOG = Logger.getLogger(DownloadUtils.class);

    /**
     * Result of a download operation.
     */
    public static class DownloadResult {
        public final long contentLength;

        public DownloadResult(long contentLength) {
            this.contentLength = contentLength;
        }
    }

    /**
     * Downloads a file from the specified URL to the given local path.
     *
     * @param downloadUrl the URL to download from (must be a valid HTTP or HTTPS URL).
     * @param downloadedFile the target path where the file will be saved.
     * @param progressMonitor an optional progress monitor to display download progress.
     * @return download result with content length
     * @throws IOException if the download fails or the file cannot be written.
     */
    public static DownloadResult download(String downloadUrl,
                                          Path downloadedFile,
                                          ProgressMonitor progressMonitor) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("Download failed with status " + response.statusCode() + ": " + downloadUrl);
            }

            // Get content length for progress tracking
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);

            // Notify progress monitor about content length before starting
            if (progressMonitor instanceof ContentLengthAware) {
                ((ContentLengthAware) progressMonitor).setContentLength(contentLength);
            }

            // Ensure parent directory exists
            Files.createDirectories(downloadedFile.getParent());

            // Download with progress tracking
            try (InputStream inputStream = response.body();
                 OutputStream fileOut = Files.newOutputStream(downloadedFile)) {

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    progressMonitor.checkCancelled();
                    fileOut.write(buffer, 0, bytesRead);
                    downloaded += bytesRead;

                    // Update progress
                    if (contentLength > 0) {
                        double fraction = (double) downloaded / contentLength;
                        progressMonitor.reportProgress(fraction * 100, "Downloading...");
                    }
                }
            }

            return new DownloadResult(contentLength);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        } finally {
            // HttpClient implements AutoCloseable only in Java 21+
            if (client instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) client).close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
