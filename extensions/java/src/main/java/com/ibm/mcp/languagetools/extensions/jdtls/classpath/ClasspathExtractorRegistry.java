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
package com.ibm.mcp.languagetools.extensions.jdtls.classpath;

import java.nio.file.Path;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Registry that selects the appropriate {@link ClasspathExtractor} for a workspace
 * based on the build tool detected (Maven or Gradle).
 */
@ApplicationScoped
public class ClasspathExtractorRegistry {

    @Inject
    Instance<ClasspathExtractor> extractors;

    /**
     * Returns the first extractor that can handle the given workspace root,
     * or {@code null} if no extractor matches.
     */
    public ClasspathExtractor getExtractor(Path workspaceRoot) {
        for (ClasspathExtractor extractor : extractors) {
            if (extractor.canHandle(workspaceRoot)) {
                return extractor;
            }
        }
        return null;
    }
}
