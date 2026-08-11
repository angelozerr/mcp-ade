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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jboss.logging.Logger;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Utility class for decompressing archives.
 * Supports: .zip, .vsix, .tar, .tar.gz, .tgz, .gz, .tar.xz, .txz
 * Inspired by lsp4ij's DownloadUtils.
 */
public class DecompressorUtils {
    private static final Logger LOG = Logger.getLogger(DecompressorUtils.class);

    @FunctionalInterface
    public interface Decompressor {
        /**
         * Decompresses the specified file into the given directory.
         *
         * @param filePath the path to the compressed file.
         * @param targetDir the directory where the contents will be extracted.
         * @param progress optional progress monitor for reporting extraction progress.
         * @return the root directory of the extracted content, or null if there is no single root.
         * @throws IOException if decompression fails.
         */
        Path decompress(Path filePath, Path targetDir, ProgressMonitor progress) throws IOException;
    }

    /**
     * Determines the appropriate decompressor based on the file's extension.
     *
     * @param filePath the path to the compressed file.
     * @return a Decompressor for the file type, or null if the extension is unsupported.
     */
    public static Decompressor getDecompressor(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);

        if (fileName.endsWith(".zip") || fileName.endsWith(".vsix")) {
            return DecompressorUtils::decompressZip;
        } else if (fileName.endsWith(".tar")) {
            return DecompressorUtils::decompressTar;
        } else if (fileName.endsWith("tar.gz") || fileName.endsWith(".tgz")) {
            return DecompressorUtils::decompressTgz;
        } else if (fileName.endsWith(".gz")) {
            return DecompressorUtils::decompressGz;
        } else if (fileName.endsWith("tar.xz") || fileName.endsWith(".txz")) {
            return DecompressorUtils::decompressTxz;
        }

        return null;
    }

    /**
     * Decompresses a ZIP archive (.zip or .vsix).
     */
    private static Path decompressZip(Path filePath, Path targetDir, ProgressMonitor progress) throws IOException {
        Files.createDirectories(targetDir);
        Set<String> topLevel = new HashSet<>();

        try (ZipFile zipFile = new ZipFile(filePath.toFile())) {
            int totalEntries = zipFile.size();
            int processed = 0;
            int reportInterval = Math.max(1, totalEntries / 50);

            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName().replace("\\", "/");

                String[] parts = entryName.split("/");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    topLevel.add(parts[0]);
                }

                Path entryPath = targetDir.resolve(entryName).normalize();
                if (!entryPath.startsWith(targetDir.normalize())) {
                    throw new IOException("Zip slip: " + entryName);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (InputStream in = zipFile.getInputStream(entry);
                         OutputStream out = Files.newOutputStream(entryPath)) {
                        in.transferTo(out);
                    }
                }

                processed++;
                if (progress != null && totalEntries > 0
                        && (processed % reportInterval == 0 || processed == totalEntries)) {
                    double pct = (double) processed / totalEntries * 100.0;
                    progress.reportProgress(pct,
                            "Extracting (" + processed + "/" + totalEntries + " files)");
                }
            }
        }

        return topLevel.size() == 1 ? targetDir.resolve(topLevel.iterator().next()) : null;
    }

    /**
     * Decompresses a TAR archive (.tar).
     */
    private static Path decompressTar(Path filePath, Path targetDir, ProgressMonitor progress) throws IOException {
        Files.createDirectories(targetDir);
        Set<String> topLevel = new HashSet<>();
        long totalSize = Files.size(filePath);
        long bytesProcessed = 0;
        int fileCount = 0;
        long lastReportedPercent = -1;

        try (InputStream fis = Files.newInputStream(filePath);
             TarArchiveInputStream tarInputStream = new TarArchiveInputStream(fis)) {

            TarArchiveEntry entry;
            while ((entry = tarInputStream.getNextTarEntry()) != null) {
                String entryName = entry.getName().replace("\\", "/");

                String[] parts = entryName.split("/");
                if (parts.length > 0 && !parts[0].isEmpty()) {
                    topLevel.add(parts[0]);
                }

                Path entryPath = targetDir.resolve(entryName).normalize();
                if (!entryPath.startsWith(targetDir.normalize())) {
                    throw new IOException("Zip slip: " + entryName);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (OutputStream out = Files.newOutputStream(entryPath)) {
                        tarInputStream.transferTo(out);
                    }

                    if ((entry.getMode() & 0100) != 0) {
                        entryPath.toFile().setExecutable(true);
                    }
                }

                bytesProcessed += entry.getSize() + 512;
                fileCount++;
                if (progress != null && totalSize > 0) {
                    long currentPercent = Math.min(bytesProcessed * 100 / totalSize, 99);
                    if (currentPercent != lastReportedPercent) {
                        lastReportedPercent = currentPercent;
                        progress.reportProgress(currentPercent,
                                "Extracting (" + fileCount + " files)");
                    }
                }
            }
        }

        return topLevel.size() == 1 ? targetDir.resolve(topLevel.iterator().next()) : null;
    }

    /**
     * Decompresses a GZIP-compressed TAR archive (.tar.gz or .tgz).
     */
    private static Path decompressTgz(Path filePath, Path targetDir, ProgressMonitor progress) throws IOException {
        try (InputStream fis = Files.newInputStream(filePath);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GZIPInputStream gzipInputStream = new GZIPInputStream(bis)) {

            Path tarFilePath = Files.createTempFile("temp", ".tar");
            try {
                Files.copy(gzipInputStream, tarFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return decompressTar(tarFilePath, targetDir, progress);
            } finally {
                Files.deleteIfExists(tarFilePath);
            }
        }
    }

    /**
     * Decompresses a GZIP file (not a TAR archive).
     */
    private static Path decompressGz(Path filePath, Path targetDir, ProgressMonitor progress) throws IOException {
        Files.createDirectories(targetDir);

        try (InputStream fis = Files.newInputStream(filePath);
             BufferedInputStream bis = new BufferedInputStream(fis);
             GZIPInputStream gzipInputStream = new GZIPInputStream(bis)) {

            Path outputFile = targetDir.resolve(stripExtension(filePath));
            Files.copy(gzipInputStream, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return outputFile;
        }
    }

    /**
     * Decompresses a XZ-compressed TAR archive (.tar.xz or .txz).
     */
    private static Path decompressTxz(Path filePath, Path targetDir, ProgressMonitor progress) throws IOException {
        try (InputStream fis = Files.newInputStream(filePath);
             BufferedInputStream bis = new BufferedInputStream(fis);
             XZInputStream xzInputStream = new XZInputStream(bis)) {

            Path tarFilePath = Files.createTempFile("temp", ".tar");
            try {
                Files.copy(xzInputStream, tarFilePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return decompressTar(tarFilePath, targetDir, progress);
            } finally {
                Files.deleteIfExists(tarFilePath);
            }
        }
    }

    /**
     * Removes the extension from a filename.
     */
    private static String stripExtension(Path path) {
        String fileName = path.getFileName().toString();
        int index = fileName.lastIndexOf('.');
        return (index > 0) ? fileName.substring(0, index) : fileName;
    }
}
