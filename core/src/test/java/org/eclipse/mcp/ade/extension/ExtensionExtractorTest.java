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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectType_folder() throws IOException {
        Path folder = tempDir.resolve("ext");
        Files.createDirectories(folder);
        assertEquals(ExtensionExtractor.SourceType.FOLDER, ExtensionExtractor.detectType(folder));
    }

    @Test
    void detectType_zip() throws IOException {
        Path zip = tempDir.resolve("ext.zip");
        Files.createFile(zip);
        assertEquals(ExtensionExtractor.SourceType.ZIP, ExtensionExtractor.detectType(zip));
    }

    @Test
    void detectType_jar() throws IOException {
        Path jar = tempDir.resolve("ext.jar");
        Files.createFile(jar);
        assertEquals(ExtensionExtractor.SourceType.JAR, ExtensionExtractor.detectType(jar));
    }

    @Test
    void detectType_unknownExtension_defaultsToZip() throws IOException {
        Path file = tempDir.resolve("ext.tar.gz");
        Files.createFile(file);
        assertEquals(ExtensionExtractor.SourceType.ZIP, ExtensionExtractor.detectType(file));
    }

    @Test
    void extract_fromFolder() throws IOException {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source.resolve("lsp/pyright"));
        Files.writeString(source.resolve("lsp/pyright/server.json"), "{}");

        Path target = tempDir.resolve("target");
        ExtensionExtractor.extract(source, target);

        assertTrue(Files.exists(target.resolve("lsp/pyright/server.json")));
        assertEquals("{}", Files.readString(target.resolve("lsp/pyright/server.json")));
    }

    @Test
    void extract_fromZip() throws IOException {
        Path zip = tempDir.resolve("ext.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("lsp/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("lsp/pyright/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("lsp/pyright/server.json"));
            zos.write("{\"name\": \"pyright\"}".getBytes());
            zos.closeEntry();
        }

        Path target = tempDir.resolve("target");
        ExtensionExtractor.extract(zip, target);

        assertTrue(Files.exists(target.resolve("lsp/pyright/server.json")));
        assertEquals("{\"name\": \"pyright\"}", Files.readString(target.resolve("lsp/pyright/server.json")));
    }

    @Test
    void extract_fromJar() throws IOException {
        Path jar = tempDir.resolve("ext.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("dap/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("dap/debugpy/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("dap/debugpy/server.json"));
            zos.write("{}".getBytes());
            zos.closeEntry();
        }

        Path target = tempDir.resolve("target");
        ExtensionExtractor.extract(jar, target);

        assertTrue(Files.exists(target.resolve("dap/debugpy/server.json")));
    }

    @Test
    void extract_zipSlipPrevention() throws IOException {
        Path zip = tempDir.resolve("evil.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../../etc/passwd"));
            zos.write("evil".getBytes());
            zos.closeEntry();
        }

        Path target = tempDir.resolve("target");
        assertThrows(IOException.class, () -> ExtensionExtractor.extract(zip, target));
    }
}
