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
package org.eclipse.mcp.ade.profile;

import java.util.List;

/**
 * A project profile identifies a build system or project type via file patterns
 * ({@code projectDetectors}) found in the workspace.
 *
 * <p>Profiles decouple project-type detection from language detection:
 * <ul>
 *   <li>A <b>language</b> (from {@code languages.json}) identifies a file's syntax
 *       (e.g., {@code .java} &rarr; language {@code "java"}).</li>
 *   <li>A <b>profile</b> identifies the <em>build system</em> via build/config files
 *       (e.g., {@code pom.xml} &rarr; profile {@code "maven"},
 *        {@code build.gradle} &rarr; profile {@code "gradle"}).</li>
 * </ul>
 *
 * <p>Profiles are <b>fine-grained</b>: each build system has its own profile.
 * This allows extensions to declare exactly which build systems they support.
 * For example, JDT.LS supports both Maven and Gradle ({@code ["maven", "gradle"]}),
 * while java-ls only supports Maven ({@code ["maven"]}).
 *
 * <p>This distinction matters when source files are too deep for the workspace
 * scan (depth 3) to find, but a build file at the root is enough to activate
 * the right extensions.
 *
 * <p><b>Use cases:</b>
 * <ul>
 *   <li>{@code pom.xml} detected at root &rarr; profile {@code "maven"} activated
 *       &rarr; JDT.LS and java-ls start (both support Maven).</li>
 *   <li>{@code build.gradle.kts} detected &rarr; profile {@code "gradle"} activated
 *       &rarr; JDT.LS starts (supports Gradle), java-ls does NOT start
 *       (only supports Maven).</li>
 *   <li>{@code *.csproj} detected &rarr; profile {@code "dotnet"} activated
 *       &rarr; .NET/OmniSharp extension starts.</li>
 *   <li>{@code .classpath} detected &rarr; profile {@code "maven"} and/or
 *       {@code "gradle"} activated (contributed by JDT.LS extension via
 *       {@code projectDetectors} in its {@code mcp-extension.json}).</li>
 * </ul>
 *
 * <p>Profiles are loaded from {@code project-profiles.json} (core) and can be
 * enriched by extensions via the {@code "profiles"} and {@code "projectDetectors"}
 * fields in {@code mcp-extension.json}.
 *
 * @param id                the unique profile identifier (e.g., {@code "maven"}, {@code "gradle"},
 *                          {@code "npm"}, {@code "cargo"})
 * @param name              the human-readable display name
 * @param projectDetectors  file name patterns that identify this project type
 *                          (exact names like {@code "pom.xml"} or globs like {@code "*.csproj"})
 * @see ProjectProfileRegistry
 */
public record ProjectProfile(String id, String name, List<String> projectDetectors) {

    public ProjectProfile {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Profile id must not be null or blank");
        }
        if (projectDetectors == null) {
            projectDetectors = List.of();
        }
    }
}
