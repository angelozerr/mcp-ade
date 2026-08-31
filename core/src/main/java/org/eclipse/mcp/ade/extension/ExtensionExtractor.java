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
package org.eclipse.mcp.ade.extension;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Utility to extract extension sources (folder, ZIP, JAR) to a target directory.
 */
public class ExtensionExtractor {

    private static final Logger LOG = Logger.getLogger(ExtensionExtractor.class);

    public enum SourceType {
        FOLDER,
        ZIP,
        JAR
    }

    private ExtensionExtractor() {
    }

    /**
     * Detect the type of the source path.
     */
    public static SourceType detectType(Path source) {
        if (Files.isDirectory(source)) {
            return SourceType.FOLDER;
        }
        String name = source.getFileName().toString().toLowerCase();
        if (name.endsWith(".jar")) {
            return SourceType.JAR;
        }
        return SourceType.ZIP;
    }

    /**
     * Extract or copy the source to the target directory.
     * For folders: recursive copy.
     * For ZIP/JAR: extract contents.
     */
    public static void extract(Path source, Path targetDir) throws IOException {
        SourceType type = detectType(source);
        switch (type) {
            case FOLDER:
                copyDirectory(source, targetDir);
                break;
            case ZIP:
            case JAR:
                extractArchive(source, targetDir);
                break;
        }
        LOG.infof("Extracted %s (%s) to %s", source, type, targetDir);
    }

    private static void copyDirectory(Path source, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path targetPath = targetDir.resolve(source.relativize(dir));
                Files.createDirectories(targetPath);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path targetPath = targetDir.resolve(source.relativize(file));
                Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void extractArchive(Path archivePath, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (InputStream fis = Files.newInputStream(archivePath);
             ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                // Security: prevent zip slip
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("Zip entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }
}
