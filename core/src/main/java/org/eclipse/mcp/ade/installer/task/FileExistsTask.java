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
import org.eclipse.mcp.ade.utils.OSUtils;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

public class FileExistsTask extends InstallerTask {
    private final String file;

    public FileExistsTask(String name, InstallerTask onSuccess, InstallerTask onFail, String file) {
        super(name, onSuccess, onFail);
        this.file = file;
    }

    @Override
    protected boolean run(InstallerContext context) {
        String resolvedPath = context.resolveVariables(file);
        boolean exists;

        if (resolvedPath.contains("*") || resolvedPath.contains("?")) {
            exists = matchesGlob(resolvedPath);
        } else {
            exists = Files.exists(Paths.get(resolvedPath));
        }

        if (exists) {
            context.traceInfo("File exists: " + resolvedPath);
        } else {
            context.traceInfo("File not found: " + resolvedPath);
        }

        return exists;
    }

    private boolean matchesGlob(String globPath) {
        String normalized = globPath.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash < 0) {
            return false;
        }
        Path dir = Paths.get(normalized.substring(0, lastSlash));
        String pattern = normalized.substring(lastSlash + 1);

        if (!Files.isDirectory(dir)) {
            return false;
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> matcher.matches(p.getFileName()));
        } catch (IOException e) {
            return false;
        }
    }

    public static class Factory extends InstallerTaskFactoryBase {
        @Override
        public String getType() {
            return "fileExists";
        }

        @Override
        protected String getDefaultName() {
            return "Check file exists";
        }

        @Override
        protected InstallerTask create(String name, InstallerTask onSuccess, InstallerTask onFail, JsonObject json) {
            String file = OSUtils.getStringFromOs(json, "file");
            return new FileExistsTask(name, onSuccess, onFail, file);
        }
    }
}
