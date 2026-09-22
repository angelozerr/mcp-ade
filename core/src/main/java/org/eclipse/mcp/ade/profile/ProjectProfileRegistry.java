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

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of project profiles loaded from {@code project-profiles.json}.
 *
 * <p>Each profile represents a <b>build system or project type</b> (e.g., Maven,
 * Gradle, npm, Cargo) — not a broad language. This fine-grained approach allows
 * extensions to declare exactly which build systems they support.
 *
 * <p>Extensions can contribute additional profiles or detectors via the
 * {@code "profiles"} and {@code "projectDetectors"} fields in their
 * {@code mcp-extension.json}. The registry merges core profiles with
 * extension contributions at startup.
 *
 * <p><b>Detection flow:</b>
 * <ol>
 *   <li>During {@code Files.walk} over the workspace, each file is passed
 *       to {@link #detectProfiles(Path)}.</li>
 *   <li>The file name is matched against all registered detector patterns
 *       (exact names and globs).</li>
 *   <li>Matching profile IDs are returned and used by
 *       {@code Application.ensureServersForWorkspace()} to start the
 *       right extensions.</li>
 * </ol>
 *
 * <p><b>Use cases:</b>
 * <ul>
 *   <li>Core defines: profile {@code "maven"} with detector {@code ["pom.xml"]},
 *       profile {@code "gradle"} with detectors {@code ["build.gradle", "build.gradle.kts"]}.</li>
 *   <li>Extension-specific detectors: JDT.LS contributes {@code ".classpath"} to
 *       its profiles ({@code "maven"}, {@code "gradle"}) via
 *       {@code "projectDetectors": [".classpath"]} in its {@code mcp-extension.json}.</li>
 *   <li>Selective activation: java-ls declares {@code "profiles": ["maven"]} so it
 *       only starts for Maven projects, while JDT.LS declares
 *       {@code "profiles": ["maven", "gradle"]} to handle both.</li>
 *   <li>Extension-created profiles: the Bazel extension contributes
 *       {@code "profiles": ["bazel"]} with {@code "projectDetectors": ["BUILD", "WORKSPACE"]}
 *       — no core change needed.</li>
 *   <li>During workspace scan, {@code pom.xml} is found at depth 0
 *       &rarr; profile {@code "maven"} detected &rarr; JDT.LS and java-ls start,
 *       even though {@code .java} files are at depth 5+.</li>
 * </ul>
 *
 * @see ProjectProfile
 */
@ApplicationScoped
public class ProjectProfileRegistry {

    private static final Logger LOG = Logger.getLogger(ProjectProfileRegistry.class);
    private static final Gson GSON = new Gson();

    private final Map<String, ProjectProfile> profiles = new ConcurrentHashMap<>();

    // O(1) lookup for exact file names (e.g. "pom.xml" → {"maven"})
    private volatile Map<String, Set<String>> exactDetectors;
    // Only glob patterns need iteration (e.g. "*.csproj")
    private volatile List<GlobDetectorEntry> globDetectors;

    public ProjectProfileRegistry() {
        loadProfiles();
        rebuildDetectors();
    }

    private void loadProfiles() {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("project-profiles.json")) {
            if (is == null) {
                LOG.warn("project-profiles.json not found on classpath");
                return;
            }
            Type listType = new TypeToken<List<ProjectProfileData>>() {}.getType();
            List<ProjectProfileData> data = GSON.fromJson(new InputStreamReader(is), listType);
            if (data != null) {
                for (ProjectProfileData d : data) {
                    profiles.put(d.id, new ProjectProfile(d.id, d.name,
                            d.projectDetectors != null ? d.projectDetectors : List.of()));
                }
            }
            LOG.infof("Loaded %d project profiles from project-profiles.json", profiles.size());
        } catch (Exception e) {
            LOG.warnf(e, "Failed to load project-profiles.json");
        }
    }

    /**
     * Contribute additional detectors from an extension's {@code mcp-extension.json}.
     * If the profile doesn't exist yet, it is created (allows extensions to define
     * entirely new profiles without modifying core).
     *
     * <p><b>Use cases:</b>
     * <ul>
     *   <li>JDT.LS contributes {@code ".classpath"} to the {@code "maven"} and
     *       {@code "gradle"} profiles (extension-specific build artifact).</li>
     *   <li>The Bazel extension creates a new {@code "bazel"} profile with
     *       detectors {@code ["BUILD", "BUILD.bazel", "WORKSPACE", "WORKSPACE.bazel"]}.</li>
     *   <li>A Julia extension creates profile {@code "julia"} with detector
     *       {@code ["Project.toml"]} — no core change needed.</li>
     * </ul>
     *
     * @param profileId    the profile to contribute to (e.g., {@code "maven"})
     * @param profileName  display name (used only when creating a new profile)
     * @param detectors    additional detector patterns to add
     */
    public void contributeDetectors(String profileId, String profileName, List<String> detectors) {
        if (profileId == null || detectors == null || detectors.isEmpty()) {
            return;
        }
        profiles.merge(profileId,
                new ProjectProfile(profileId, profileName != null ? profileName : profileId, detectors),
                (existing, contributed) -> {
                    Set<String> merged = new LinkedHashSet<>(existing.projectDetectors());
                    merged.addAll(contributed.projectDetectors());
                    return new ProjectProfile(existing.id(),
                            existing.name(),
                            List.copyOf(merged));
                });
        rebuildDetectors();
    }

    /**
     * Detect which profile IDs match a given file path.
     * Called during {@code Files.walk} for each file encountered.
     *
     * <p>Performance: exact file names (e.g., {@code pom.xml}) are resolved via
     * O(1) HashMap lookup. Only glob patterns (e.g., {@code *.csproj}) require
     * iteration, and those are typically few.
     *
     * <p><b>Examples:</b>
     * <ul>
     *   <li>{@code detectProfiles(Path.of("pom.xml"))} &rarr; {@code {"maven"}}</li>
     *   <li>{@code detectProfiles(Path.of("build.gradle"))} &rarr; {@code {"gradle"}}</li>
     *   <li>{@code detectProfiles(Path.of("build.gradle.kts"))} &rarr; {@code {"gradle"}}</li>
     *   <li>{@code detectProfiles(Path.of("Foo.java"))} &rarr; {@code {}} (no profile, only language)</li>
     *   <li>{@code detectProfiles(Path.of("MyApp.csproj"))} &rarr; {@code {"dotnet"}}</li>
     *   <li>{@code detectProfiles(Path.of("Cargo.toml"))} &rarr; {@code {"cargo"}}</li>
     * </ul>
     *
     * @param filePath the file path to test (only the file name is used for matching)
     * @return set of matching profile IDs (empty if none match)
     */
    public Set<String> detectProfiles(Path filePath) {
        if (filePath == null) {
            return Set.of();
        }
        ensureDetectors();
        Path fileName = filePath.getFileName();
        String name = fileName.toString();

        Set<String> exactMatch = exactDetectors.get(name);
        if (globDetectors.isEmpty()) {
            return exactMatch != null ? exactMatch : Set.of();
        }

        Set<String> matched = null;
        if (exactMatch != null) {
            matched = new HashSet<>(exactMatch);
        }
        for (GlobDetectorEntry entry : globDetectors) {
            if (entry.pathMatcher.matches(fileName)) {
                if (matched == null) {
                    matched = new HashSet<>();
                }
                matched.add(entry.profileId);
            }
        }
        return matched != null ? matched : Set.of();
    }

    /**
     * Returns the profile with the given ID, or {@code null} if not found.
     *
     * @param profileId the profile ID to look up
     * @return the profile, or {@code null}
     */
    public ProjectProfile getProfile(String profileId) {
        return profiles.get(profileId);
    }

    /**
     * Returns all registered profiles.
     */
    public Collection<ProjectProfile> getAllProfiles() {
        return Collections.unmodifiableCollection(profiles.values());
    }

    /**
     * Returns all registered profile IDs.
     */
    public Set<String> getProfileIds() {
        return Collections.unmodifiableSet(profiles.keySet());
    }

    private void ensureDetectors() {
        if (this.exactDetectors == null) {
            rebuildDetectors();
        }
    }

    private synchronized void rebuildDetectors() {
        Map<String, Set<String>> exact = new HashMap<>();
        List<GlobDetectorEntry> globs = new ArrayList<>();
        for (ProjectProfile profile : profiles.values()) {
            for (String pattern : profile.projectDetectors()) {
                if (isGlobPattern(pattern)) {
                    try {
                        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                        globs.add(new GlobDetectorEntry(profile.id(), pathMatcher));
                    } catch (Exception e) {
                        LOG.warnf("Invalid glob pattern '%s' in profile '%s'", pattern, profile.id());
                    }
                } else {
                    exact.computeIfAbsent(pattern, k -> new HashSet<>()).add(profile.id());
                }
            }
        }
        // Freeze exact match sets for safe concurrent reads
        exact.replaceAll((k, v) -> Set.copyOf(v));
        this.exactDetectors = Map.copyOf(exact);
        this.globDetectors = List.copyOf(globs);
    }

    private static boolean isGlobPattern(String pattern) {
        return pattern.contains("*") || pattern.contains("?")
                || pattern.contains("[") || pattern.contains("{");
    }

    private static class GlobDetectorEntry {
        final String profileId;
        final PathMatcher pathMatcher;

        GlobDetectorEntry(String profileId, PathMatcher pathMatcher) {
            this.profileId = profileId;
            this.pathMatcher = pathMatcher;
        }
    }

    /**
     * POJO for Gson deserialization of {@code project-profiles.json} entries.
     */
    private static class ProjectProfileData {
        String id;
        String name;
        List<String> projectDetectors;
    }
}
