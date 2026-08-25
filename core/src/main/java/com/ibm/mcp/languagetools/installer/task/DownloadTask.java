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
package com.ibm.mcp.languagetools.installer.task;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.mcp.languagetools.installer.InstallerContext;
import com.ibm.mcp.languagetools.installer.download.*;
import com.ibm.mcp.languagetools.progress.AbstractProgressMonitor;
import com.ibm.mcp.languagetools.progress.MultiProgressMonitor;
import com.ibm.mcp.languagetools.progress.NoOpProgressMonitor;
import com.ibm.mcp.languagetools.progress.ProgressMonitor;
import com.ibm.mcp.languagetools.utils.OSUtils;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Task that downloads and extracts a file.
 * Supports direct URL, GitHub releases, and Maven artifacts.
 */
public class DownloadTask extends InstallerTask {
    private static final Logger LOG = Logger.getLogger(DownloadTask.class);

    private final String url;  // Fallback URL if asset fetcher fails
    private final AssetFetcherInfo assetFetcherInfo;  // GitHub or Maven fetcher
    private final OutputInfo outputInfo;

    public DownloadTask(String name, InstallerTask onSuccess, String url,
                       AssetFetcherInfo assetFetcherInfo, OutputInfo outputInfo) {
        super(name, onSuccess);
        this.url = url;
        this.assetFetcherInfo = assetFetcherInfo;
        this.outputInfo = outputInfo;
    }

    /**
     * Output information for download task.
     */
    public record OutputInfo(String outputDir, String outputFileName, boolean executable, boolean stripRootDir) {
    }

    @Override
    protected boolean run(InstallerContext context) {
        // Get download URL (try asset fetcher first, fallback to direct URL)
        String resolvedUrl = getDownloadUrl(context);
        if (resolvedUrl == null) {
            throw new IllegalStateException("No download URL available for '" + getName() + "'");
        }

        String resolvedOutputDir = context.resolveVariables(outputInfo.outputDir());

        context.traceInfo("Downloading from: " + resolvedUrl);

        try {
            Path outputPath = Paths.get(resolvedOutputDir);
            Files.createDirectories(outputPath);

            String fileName = resolvedUrl.substring(resolvedUrl.lastIndexOf('/') + 1);
            if (fileName.contains("?")) {
                fileName = fileName.substring(0, fileName.indexOf('?'));
            }
            Path downloadedFile = Files.createTempFile("download-", fileName);

            try {
                context.getProgress().beginStep(getName());
                ProgressMonitorWrapper downloadProgress = new ProgressMonitorWrapper(context, getName());
                DownloadUtils.DownloadResult result = DownloadUtils.download(resolvedUrl, downloadedFile, downloadProgress);

                DecompressorUtils.Decompressor decompressor = DecompressorUtils.getDecompressor(downloadedFile);
                context.getProgress().beginStep(getExtractStepName(getName()));
                if (decompressor != null) {
                    context.traceUpdate("Extracting " + getName());
                    ProgressMonitor extractProgress = new MultiProgressMonitor(
                            context.getProgress(),
                            new NoOpProgressMonitor() {
                                @Override
                                public void reportProgress(double progress, String message) {
                                    context.traceUpdate(message);
                                }
                            }
                    );
                    Path rootDir = decompressor.decompress(downloadedFile, outputPath, extractProgress);
                    if (rootDir != null && outputInfo.stripRootDir()) {
                        stripRootDir(rootDir, outputPath, context);
                    }
                } else {
                    context.getProgress().reportProgress("Installing " + getName());
                    context.traceUpdate("Installing " + getName());

                    String targetFileName = outputInfo.outputFileName() != null ? context.resolveVariables(outputInfo.outputFileName()) : fileName;
                    Path targetFile = outputPath.resolve(targetFileName);
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(downloadedFile, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    context.traceInfo("File copied to: " + targetFile);

                    if (outputInfo.executable()) {
                        context.traceInfo("Setting executable permission: " + targetFile);
                        targetFile.toFile().setExecutable(true, false);
                    }
                }

                context.setVariable("output.dir", resolvedOutputDir);
                if (outputInfo.outputFileName() != null) {
                    String resolvedFileName = context.resolveVariables(outputInfo.outputFileName());
                    context.setVariable("output.file.name", resolvedFileName);
                }

                context.traceInfo("Downloaded and extracted to: " + resolvedOutputDir);

                return true;

            } finally {
                Files.deleteIfExists(downloadedFile);
            }

        } catch (Exception e) {
            LOG.errorf(e, "Download failed: %s", resolvedUrl);
            context.traceError("Download failed: " + e.getMessage());
            throw new IllegalStateException("Download '" + getName() + "' failed: " + e.getMessage(), e);
        }
    }

    /**
     * Moves all contents of rootDir into its parent (outputPath) and deletes rootDir.
     * Used when an archive contains a single top-level directory with a dynamic name
     * (e.g., clangd_snapshot_20260712/) that should be stripped.
     */
    private void stripRootDir(Path rootDir, Path outputPath, InstallerContext context) {
        try {
            context.traceInfo("Stripping root directory: " + rootDir.getFileName());
            try (var children = Files.list(rootDir)) {
                for (Path child : children.toList()) {
                    Path target = outputPath.resolve(child.getFileName());
                    Files.move(child, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.deleteIfExists(rootDir);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to strip root directory: %s", rootDir);
        }
    }

    /**
     * Derive the extract step name from a download task name.
     * "Download vscode-js-debug" → "Extract vscode-js-debug"
     */
    public static String getExtractStepName(String downloadName) {
        if (downloadName.startsWith("Download ")) {
            return "Extract " + downloadName.substring("Download ".length());
        }
        return "Extract " + downloadName;
    }

    /**
     * Get download URL - try asset fetcher (GitHub/Maven) first, then fallback to direct URL.
     */
    private String getDownloadUrl(InstallerContext context) {
        if (assetFetcherInfo != null) {
            AssetFetcherReporter reporter = new AssetFetcherReporter(context);

            String fetchedUrl = assetFetcherInfo.assetFetcher().getDownloadUrl(
                assetFetcherInfo.releaseMatcher(),
                assetFetcherInfo.assetMatcher(),
                reporter
            );

            if (fetchedUrl != null) {
                return context.resolveVariables(fetchedUrl);
            }

            context.traceInfo("Asset fetcher failed, falling back to direct URL");
        }

        return url != null ? context.resolveVariables(url) : null;
    }

    /**
     * Factory for DownloadTask.
     */
    public static class Factory extends InstallerTaskFactoryBase {
        private static final String URL_JSON_PROPERTY = "url";
        private static final String OUTPUT_JSON_PROPERTY = "output";
        private static final String OUTPUT_DIR_JSON_PROPERTY = "dir";
        private static final String OUTPUT_FILE_JSON_PROPERTY = "file";
        private static final String OUTPUT_FILE_NAME_JSON_PROPERTY = "name";
        private static final String OUTPUT_FILE_EXECUTABLE_JSON_PROPERTY = "executable";
        private static final String OUTPUT_STRIP_ROOT_DIR_JSON_PROPERTY = "stripRootDir";

        @Override
        public String getType() {
            return "download";
        }

        @Override
        protected String getDefaultName() {
            return "Download";
        }

        @Override
        protected InstallerTask create(String name, InstallerTask onSuccess, JsonObject obj) {
            String url = OSUtils.getStringFromOs(obj, URL_JSON_PROPERTY);
            AssetFetcherInfo assetFetcherInfo = getAssetFetcher(obj);
            OutputInfo outputInfo = getOutputInfo(obj);
            return new DownloadTask(name, onSuccess, url, assetFetcherInfo, outputInfo);
        }

        private static volatile List<AssetFetcherProvider> assetFetcherProviders;

        private static List<AssetFetcherProvider> getAssetFetcherProviders() {
            if (assetFetcherProviders == null) {
                List<AssetFetcherProvider> providers = new ArrayList<>();
                ServiceLoader.load(AssetFetcherProvider.class).forEach(providers::add);
                assetFetcherProviders = providers;
            }
            return assetFetcherProviders;
        }

        private AssetFetcherInfo getAssetFetcher(JsonObject json) {
            for (AssetFetcherProvider provider : getAssetFetcherProviders()) {
                AssetFetcherInfo info = provider.getAssetFetcher(json);
                if (info != null) {
                    return info;
                }
            }
            return null;
        }

        private OutputInfo getOutputInfo(JsonObject json) {
            if (!json.has(OUTPUT_JSON_PROPERTY)) {
                return null;
            }
            JsonElement outputElement = json.get(OUTPUT_JSON_PROPERTY);
            if (!outputElement.isJsonObject()) {
                return null;
            }
            JsonObject outputObj = outputElement.getAsJsonObject();
            String dir = OSUtils.getStringFromOs(outputObj, OUTPUT_DIR_JSON_PROPERTY);

            String fileName = null;
            boolean executable = false;
            if (outputObj.has(OUTPUT_FILE_JSON_PROPERTY)) {
                JsonElement fileElement = outputObj.get(OUTPUT_FILE_JSON_PROPERTY);
                if (fileElement.isJsonObject()) {
                    JsonObject fileObj = fileElement.getAsJsonObject();
                    fileName = OSUtils.getStringFromOs(fileObj, OUTPUT_FILE_NAME_JSON_PROPERTY);
                    executable = fileObj.has(OUTPUT_FILE_EXECUTABLE_JSON_PROPERTY) && fileObj.get(OUTPUT_FILE_EXECUTABLE_JSON_PROPERTY).getAsBoolean();
                }
            }
            boolean stripRootDir = outputObj.has(OUTPUT_STRIP_ROOT_DIR_JSON_PROPERTY) && outputObj.get(OUTPUT_STRIP_ROOT_DIR_JSON_PROPERTY).getAsBoolean();
            return new OutputInfo(dir, fileName, executable, stripRootDir);
        }

    }

    /**
     * Wrapper for ProgressMonitor that tracks download progress with trace updates.
     */
    private static class ProgressMonitorWrapper extends AbstractProgressMonitor implements ContentLengthAware {
        private final InstallerContext context;
        private final String name;
        private long contentLength = -1;

        public ProgressMonitorWrapper(InstallerContext context, String name) {
            this.context = context;
            this.name = name;
        }

        public void setContentLength(long contentLength) {
            this.contentLength = contentLength;
        }

        @Override
        public void reportProgress(double progress, String message) {
            String progressMessage;
            if (contentLength > 0) {
                double fraction = progress / 100.0;
                long downloaded = (long) (fraction * contentLength);
                String downloadedMB = String.format("%.1f", downloaded / 1024.0 / 1024.0);
                String totalMB = String.format("%.1f", contentLength / 1024.0 / 1024.0);
                progressMessage = String.format("Downloading %s: %s MB / %s MB (%.0f%%)",
                        name, downloadedMB, totalMB, progress);
            } else {
                progressMessage = String.format("Downloading %s (%.0f%%)", name, progress);
            }

            context.getProgress().reportProgress(progress, progressMessage);
            context.traceUpdate(progressMessage);
        }

        @Override
        public void reportProgress(String message) {
            context.getProgress().reportProgress(message);
        }

        @Override
        public void setComplete() {
            context.getProgress().setComplete();
        }

        @Override
        public boolean isCancelled() {
            return context.getProgress().isCancelled();
        }

        @Override
        public void checkCancelled() {
            context.checkCanceled();
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<T> executeWithCancellation(java.util.concurrent.CompletableFuture<T> future) {
            return context.getProgress().executeWithCancellation(future);
        }

        @Override
        public boolean isSupported() {
            return context.getProgress().isSupported();
        }
    }

    /**
     * Reporter implementation for AssetFetcher that logs to InstallerContext traces.
     */
    private static class AssetFetcherReporter implements AssetFetcher.Reporter {
        private final InstallerContext context;

        public AssetFetcherReporter(InstallerContext context) {
            this.context = context;
        }

        @Override
        public void setText(String text) {
            context.traceInfo(text);
        }

        @Override
        public void setText(String text, Exception e) {
            context.traceError(text + ": " + e.getMessage());
        }
    }
}
