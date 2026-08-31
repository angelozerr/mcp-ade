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
package org.eclipse.mcp.ade.variable;

import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Resolves {@code ${vscodeExtension:id}} variables by scanning
 * {@code ~/.vscode/extensions/} for a directory matching the extension ID.
 * When multiple versions are installed, the lexicographically latest is picked.
 */
@Singleton
public class VscodeVariableResolver implements VariableResolver {

    private static final Logger LOG = Logger.getLogger(VscodeVariableResolver.class);
    private static final String PREFIX = "vscodeExtension";

    @Override
    public String resolve(VariableExpression expression, VariableContext context) {
        if (!PREFIX.equals(expression.prefix())) {
            return null;
        }
        return findExtensionPath(expression.name());
    }

    private static String findExtensionPath(String extensionId) {
        Path vscodeExtDir = Path.of(System.getProperty("user.home"), ".vscode", "extensions");
        if (!Files.isDirectory(vscodeExtDir)) {
            LOG.debugf("VS Code extensions directory not found: %s", vscodeExtDir);
            return null;
        }

        String prefix = extensionId.toLowerCase() + "-";
        try (Stream<Path> entries = Files.list(vscodeExtDir)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().toLowerCase().startsWith(prefix))
                    .max((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()))
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException e) {
            LOG.debugf(e, "Failed to list VS Code extensions directory");
            return null;
        }
    }
}
