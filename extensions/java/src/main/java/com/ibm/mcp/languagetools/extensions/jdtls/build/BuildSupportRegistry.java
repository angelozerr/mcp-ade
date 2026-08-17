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
package com.ibm.mcp.languagetools.extensions.jdtls.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Registry that selects the appropriate {@link BuildSupport} for a workspace
 * based on the build tool detected (Maven or Gradle).
 *
 * <p>Implementations are discovered via Java SPI ({@link ServiceLoader}).</p>
 */
@ApplicationScoped
public class BuildSupportRegistry {

    private static final Logger LOG = Logger.getLogger(BuildSupportRegistry.class);

    private final List<BuildSupport> buildSupports = new ArrayList<>();

    public BuildSupportRegistry() {
        ServiceLoader<BuildSupport> loader = ServiceLoader.load(BuildSupport.class);
        for (BuildSupport support : loader) {
            buildSupports.add(support);
            LOG.infof("Registered build support: %s", support.getClass().getSimpleName());
        }
    }

    /**
     * Returns the first build support that can handle the given workspace root,
     * or {@code null} if none matches.
     */
    public BuildSupport getBuildSupport(Path workspaceRoot) {
        for (BuildSupport support : buildSupports) {
            if (support.canHandle(workspaceRoot)) {
                return support;
            }
        }
        return null;
    }

    /**
     * Returns whether any registered build support recognizes a build file
     * in the given directory.
     */
    public boolean hasBuildFile(Path dir) {
        for (BuildSupport support : buildSupports) {
            if (support.canHandle(dir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Discovers sibling modules under a parent directory.
     *
     * <p>First tries the matching build support's {@link BuildSupport#discoverSubModules}
     * (e.g., Maven reads {@code <modules>} from pom.xml). Falls back to scanning
     * immediate child directories that contain a build file.</p>
     */
    public List<Path> discoverSiblingModules(Path parentDir) {
        for (BuildSupport support : buildSupports) {
            if (support.canHandle(parentDir)) {
                List<String> subModules = support.discoverSubModules(parentDir);
                if (!subModules.isEmpty()) {
                    return subModules.stream()
                            .map(parentDir::resolve)
                            .filter(Files::isDirectory)
                            .filter(this::hasBuildFile)
                            .toList();
                }
            }
        }

        try (var entries = Files.list(parentDir)) {
            return entries.filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("."))
                    .filter(this::hasBuildFile)
                    .toList();
        } catch (IOException e) {
            LOG.debugf("Cannot list directory: %s", parentDir);
            return List.of();
        }
    }
}
