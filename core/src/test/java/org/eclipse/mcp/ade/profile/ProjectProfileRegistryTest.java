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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectProfileRegistryTest {

    private ProjectProfileRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ProjectProfileRegistry();
    }

    // --- Loading from project-profiles.json ---

    @Test
    void loadsCoreProfiles() {
        assertNotNull(registry.getProfile("maven"));
        assertNotNull(registry.getProfile("gradle"));
        assertNotNull(registry.getProfile("npm"));
        assertNotNull(registry.getProfile("cargo"));
        assertNotNull(registry.getProfile("go-mod"));
        assertNotNull(registry.getProfile("dotnet"));
        assertNotNull(registry.getProfile("pub"));
    }

    @Test
    void loadsPythonBuildProfiles() {
        assertNotNull(registry.getProfile("pip"));
        assertNotNull(registry.getProfile("setuptools"));
        assertNotNull(registry.getProfile("pyproject"));
    }

    @Test
    void loadsRubyBuildProfiles() {
        assertNotNull(registry.getProfile("bundler"));
        assertNotNull(registry.getProfile("rubygems"));
    }

    @Test
    void loadsHaskellBuildProfiles() {
        assertNotNull(registry.getProfile("cabal"));
        assertNotNull(registry.getProfile("stack"));
    }

    @Test
    void loadsClojureBuildProfiles() {
        assertNotNull(registry.getProfile("leiningen"));
        assertNotNull(registry.getProfile("deps-edn"));
    }

    @Test
    void loadsOcamlBuildProfiles() {
        assertNotNull(registry.getProfile("dune"));
        assertNotNull(registry.getProfile("opam"));
    }

    @Test
    void loadsOtherProfiles() {
        assertNotNull(registry.getProfile("sbt"));
        assertNotNull(registry.getProfile("swift-pm"));
        assertNotNull(registry.getProfile("xcode"));
        assertNotNull(registry.getProfile("mix"));
        assertNotNull(registry.getProfile("latex"));
        assertNotNull(registry.getProfile("terraform"));
        assertNotNull(registry.getProfile("nix"));
        assertNotNull(registry.getProfile("zig-build"));
        assertNotNull(registry.getProfile("fortran"));
        assertNotNull(registry.getProfile("gpr"));
        assertNotNull(registry.getProfile("elm"));
    }

    @Test
    void noOldBroadLanguageProfiles() {
        assertNull(registry.getProfile("java"), "Broad 'java' profile should not exist");
        assertNull(registry.getProfile("kotlin"), "Broad 'kotlin' profile should not exist");
        assertNull(registry.getProfile("python"), "Broad 'python' profile should not exist");
        assertNull(registry.getProfile("javascript"), "Broad 'javascript' profile should not exist");
        assertNull(registry.getProfile("csharp"), "Broad 'csharp' profile should not exist");
        assertNull(registry.getProfile("go"), "Broad 'go' profile should not exist");
        assertNull(registry.getProfile("rust"), "Broad 'rust' profile should not exist");
        assertNull(registry.getProfile("dart"), "Broad 'dart' profile should not exist");
        assertNull(registry.getProfile("ruby"), "Broad 'ruby' profile should not exist");
        assertNull(registry.getProfile("scala"), "Broad 'scala' profile should not exist");
        assertNull(registry.getProfile("swift"), "Broad 'swift' profile should not exist");
        assertNull(registry.getProfile("haskell"), "Broad 'haskell' profile should not exist");
        assertNull(registry.getProfile("clojure"), "Broad 'clojure' profile should not exist");
        assertNull(registry.getProfile("ocaml"), "Broad 'ocaml' profile should not exist");
        assertNull(registry.getProfile("ada"), "Broad 'ada' profile should not exist");
        assertNull(registry.getProfile("zig"), "Broad 'zig' profile should not exist");
        assertNull(registry.getProfile("elixir"), "Broad 'elixir' profile should not exist");
    }

    // --- Maven profile detectors ---

    @Test
    void mavenProfileHasPomXml() {
        ProjectProfile maven = registry.getProfile("maven");
        assertNotNull(maven);
        assertEquals("Maven", maven.name());
        assertTrue(maven.projectDetectors().contains("pom.xml"));
        assertEquals(1, maven.projectDetectors().size());
    }

    // --- Gradle profile detectors ---

    @Test
    void gradleProfileHasBuildGradleAndKts() {
        ProjectProfile gradle = registry.getProfile("gradle");
        assertNotNull(gradle);
        assertEquals("Gradle", gradle.name());
        assertTrue(gradle.projectDetectors().contains("build.gradle"));
        assertTrue(gradle.projectDetectors().contains("build.gradle.kts"));
        assertEquals(2, gradle.projectDetectors().size());
    }

    // --- .NET profile detectors ---

    @Test
    void dotnetProfileHasGlobDetectors() {
        ProjectProfile dotnet = registry.getProfile("dotnet");
        assertNotNull(dotnet);
        assertTrue(dotnet.projectDetectors().contains("*.csproj"));
        assertTrue(dotnet.projectDetectors().contains("*.sln"));
    }

    // --- npm profile detectors ---

    @Test
    void npmProfileHasPackageJsonAndTsconfig() {
        ProjectProfile npm = registry.getProfile("npm");
        assertNotNull(npm);
        assertTrue(npm.projectDetectors().contains("package.json"));
        assertTrue(npm.projectDetectors().contains("tsconfig.json"));
    }

    // =========================================================
    // Exact name detection — Java ecosystem
    // =========================================================

    @Test
    void detectsPomXmlAsMaven() {
        Set<String> profiles = registry.detectProfiles(Path.of("pom.xml"));
        assertTrue(profiles.contains("maven"));
        assertFalse(profiles.contains("gradle"));
    }

    @Test
    void detectsBuildGradleAsGradle() {
        Set<String> profiles = registry.detectProfiles(Path.of("build.gradle"));
        assertTrue(profiles.contains("gradle"));
        assertFalse(profiles.contains("maven"));
    }

    @Test
    void detectsBuildGradleKtsAsGradle() {
        Set<String> profiles = registry.detectProfiles(Path.of("build.gradle.kts"));
        assertTrue(profiles.contains("gradle"));
        assertFalse(profiles.contains("maven"),
                "build.gradle.kts should not match maven profile");
    }

    @Test
    void pomXmlDoesNotTriggerGradle() {
        Set<String> profiles = registry.detectProfiles(Path.of("pom.xml"));
        assertFalse(profiles.contains("gradle"));
    }

    @Test
    void buildGradleDoesNotTriggerMaven() {
        Set<String> profiles = registry.detectProfiles(Path.of("build.gradle"));
        assertFalse(profiles.contains("maven"));
    }

    // =========================================================
    // Exact name detection — other build systems
    // =========================================================

    @Test
    void detectsGoMod() {
        Set<String> profiles = registry.detectProfiles(Path.of("go.mod"));
        assertTrue(profiles.contains("go-mod"));
    }

    @Test
    void detectsCargoToml() {
        Set<String> profiles = registry.detectProfiles(Path.of("Cargo.toml"));
        assertTrue(profiles.contains("cargo"));
    }

    @Test
    void detectsPackageJsonAsNpm() {
        Set<String> profiles = registry.detectProfiles(Path.of("package.json"));
        assertTrue(profiles.contains("npm"));
    }

    @Test
    void detectsTsconfigJsonAsNpm() {
        Set<String> profiles = registry.detectProfiles(Path.of("tsconfig.json"));
        assertTrue(profiles.contains("npm"));
    }

    @Test
    void detectsPubspecYaml() {
        Set<String> profiles = registry.detectProfiles(Path.of("pubspec.yaml"));
        assertTrue(profiles.contains("pub"));
    }

    @Test
    void detectsRequirementsTxtAsPip() {
        Set<String> profiles = registry.detectProfiles(Path.of("requirements.txt"));
        assertTrue(profiles.contains("pip"));
    }

    @Test
    void detectsSetupPyAsSetuptools() {
        Set<String> profiles = registry.detectProfiles(Path.of("setup.py"));
        assertTrue(profiles.contains("setuptools"));
    }

    @Test
    void detectsPyprojectTomlAsPyproject() {
        Set<String> profiles = registry.detectProfiles(Path.of("pyproject.toml"));
        assertTrue(profiles.contains("pyproject"));
    }

    @Test
    void detectsGemfileAsBundler() {
        Set<String> profiles = registry.detectProfiles(Path.of("Gemfile"));
        assertTrue(profiles.contains("bundler"));
    }

    @Test
    void detectsBuildSbtAsSbt() {
        Set<String> profiles = registry.detectProfiles(Path.of("build.sbt"));
        assertTrue(profiles.contains("sbt"));
    }

    @Test
    void detectsPackageSwiftAsSwiftPm() {
        Set<String> profiles = registry.detectProfiles(Path.of("Package.swift"));
        assertTrue(profiles.contains("swift-pm"));
    }

    @Test
    void detectsMixExsAsMix() {
        Set<String> profiles = registry.detectProfiles(Path.of("mix.exs"));
        assertTrue(profiles.contains("mix"));
    }

    @Test
    void detectsStackYamlAsStack() {
        Set<String> profiles = registry.detectProfiles(Path.of("stack.yaml"));
        assertTrue(profiles.contains("stack"));
    }

    @Test
    void detectsProjectCljAsLeiningen() {
        Set<String> profiles = registry.detectProfiles(Path.of("project.clj"));
        assertTrue(profiles.contains("leiningen"));
    }

    @Test
    void detectsDepsEdnAsClojureDeps() {
        Set<String> profiles = registry.detectProfiles(Path.of("deps.edn"));
        assertTrue(profiles.contains("deps-edn"));
    }

    @Test
    void detectsFlakeNixAsNix() {
        Set<String> profiles = registry.detectProfiles(Path.of("flake.nix"));
        assertTrue(profiles.contains("nix"));
    }

    @Test
    void detectsDefaultNixAsNix() {
        Set<String> profiles = registry.detectProfiles(Path.of("default.nix"));
        assertTrue(profiles.contains("nix"));
    }

    @Test
    void detectsBuildZigAsZigBuild() {
        Set<String> profiles = registry.detectProfiles(Path.of("build.zig"));
        assertTrue(profiles.contains("zig-build"));
    }

    @Test
    void detectsDuneProjectAsDune() {
        Set<String> profiles = registry.detectProfiles(Path.of("dune-project"));
        assertTrue(profiles.contains("dune"));
    }

    @Test
    void detectsElmJsonAsElm() {
        Set<String> profiles = registry.detectProfiles(Path.of("elm.json"));
        assertTrue(profiles.contains("elm"));
    }

    // =========================================================
    // Glob detection
    // =========================================================

    @Test
    void detectsCsprojViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("MyApp.csproj"));
        assertTrue(profiles.contains("dotnet"));
    }

    @Test
    void detectsSlnViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("MyApp.sln"));
        assertTrue(profiles.contains("dotnet"));
    }

    @Test
    void detectsGemspecViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("mygem.gemspec"));
        assertTrue(profiles.contains("rubygems"));
    }

    @Test
    void detectsCabalViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("myproject.cabal"));
        assertTrue(profiles.contains("cabal"));
    }

    @Test
    void detectsXcodeprojViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("MyApp.xcodeproj"));
        assertTrue(profiles.contains("xcode"));
    }

    @Test
    void detectsTfViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("main.tf"));
        assertTrue(profiles.contains("terraform"));
    }

    @Test
    void detectsTexViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("paper.tex"));
        assertTrue(profiles.contains("latex"));
    }

    @Test
    void detectsOpamViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("mylib.opam"));
        assertTrue(profiles.contains("opam"));
    }

    @Test
    void detectsGprViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("myproject.gpr"));
        assertTrue(profiles.contains("gpr"));
    }

    @Test
    void detectsFortranF90ViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("main.f90"));
        assertTrue(profiles.contains("fortran"));
    }

    @Test
    void detectsFortranF95ViaGlob() {
        Set<String> profiles = registry.detectProfiles(Path.of("module.f95"));
        assertTrue(profiles.contains("fortran"));
    }

    // =========================================================
    // No match — source files are languages, not profiles
    // =========================================================

    @Test
    void noMatchForUnknownFile() {
        Set<String> profiles = registry.detectProfiles(Path.of("README.md"));
        assertTrue(profiles.isEmpty());
    }

    @Test
    void noMatchForJavaSourceFile() {
        Set<String> profiles = registry.detectProfiles(Path.of("Main.java"));
        assertTrue(profiles.isEmpty(), "Source files are detected by language, not profile");
    }

    @Test
    void noMatchForPythonSourceFile() {
        Set<String> profiles = registry.detectProfiles(Path.of("app.py"));
        assertTrue(profiles.isEmpty(), ".py files are detected by language, not profile");
    }

    @Test
    void noMatchForGoSourceFile() {
        Set<String> profiles = registry.detectProfiles(Path.of("main.go"));
        assertTrue(profiles.isEmpty(), ".go files are detected by language, not profile");
    }

    @Test
    void noMatchForRustSourceFile() {
        Set<String> profiles = registry.detectProfiles(Path.of("main.rs"));
        assertTrue(profiles.isEmpty(), ".rs files are detected by language, not profile");
    }

    @Test
    void noMatchForTypeScriptSourceFile() {
        Set<String> profiles = registry.detectProfiles(Path.of("app.ts"));
        assertTrue(profiles.isEmpty(), ".ts files are detected by language, not profile");
    }

    @Test
    void noMatchForNull() {
        Set<String> profiles = registry.detectProfiles(null);
        assertTrue(profiles.isEmpty());
    }

    // =========================================================
    // Mutual exclusion — different build systems don't overlap
    // =========================================================

    @Test
    void mavenAndGradleAreSeparate() {
        Set<String> pomProfiles = registry.detectProfiles(Path.of("pom.xml"));
        Set<String> gradleProfiles = registry.detectProfiles(Path.of("build.gradle"));

        assertTrue(pomProfiles.contains("maven"));
        assertFalse(pomProfiles.contains("gradle"));
        assertTrue(gradleProfiles.contains("gradle"));
        assertFalse(gradleProfiles.contains("maven"));
    }

    @Test
    void pipAndSetuptoolsAndPyprojectAreSeparate() {
        Set<String> reqProfiles = registry.detectProfiles(Path.of("requirements.txt"));
        Set<String> setupProfiles = registry.detectProfiles(Path.of("setup.py"));
        Set<String> pyprojectProfiles = registry.detectProfiles(Path.of("pyproject.toml"));

        assertEquals(Set.of("pip"), reqProfiles);
        assertEquals(Set.of("setuptools"), setupProfiles);
        assertEquals(Set.of("pyproject"), pyprojectProfiles);
    }

    @Test
    void cabalAndStackAreSeparate() {
        Set<String> stackProfiles = registry.detectProfiles(Path.of("stack.yaml"));
        assertTrue(stackProfiles.contains("stack"));
        assertFalse(stackProfiles.contains("cabal"));
    }

    @Test
    void leiningenAndDepsEdnAreSeparate() {
        Set<String> leinProfiles = registry.detectProfiles(Path.of("project.clj"));
        Set<String> depsProfiles = registry.detectProfiles(Path.of("deps.edn"));

        assertEquals(Set.of("leiningen"), leinProfiles);
        assertEquals(Set.of("deps-edn"), depsProfiles);
    }

    @Test
    void duneAndOpamAreSeparate() {
        Set<String> duneProfiles = registry.detectProfiles(Path.of("dune-project"));
        assertEquals(Set.of("dune"), duneProfiles);
    }

    // =========================================================
    // File path with directories — only file name matters
    // =========================================================

    @Test
    void detectsProfileFromNestedPath() {
        Set<String> profiles = registry.detectProfiles(Path.of("src/main/pom.xml"));
        assertTrue(profiles.contains("maven"));
    }

    @Test
    void detectsGlobFromNestedPath() {
        Set<String> profiles = registry.detectProfiles(Path.of("project/MyApp.csproj"));
        assertTrue(profiles.contains("dotnet"));
    }

    // =========================================================
    // Extension contributions — new profiles and detectors
    // =========================================================

    @Test
    void contributeNewProfile() {
        assertNull(registry.getProfile("julia"));

        registry.contributeDetectors("julia", "Julia", List.of("Project.toml"));

        ProjectProfile julia = registry.getProfile("julia");
        assertNotNull(julia);
        assertEquals("julia", julia.id());
        assertTrue(julia.projectDetectors().contains("Project.toml"));

        Set<String> profiles = registry.detectProfiles(Path.of("Project.toml"));
        assertTrue(profiles.contains("julia"));
    }

    @Test
    void contributeBazelProfileFromExtension() {
        assertNull(registry.getProfile("bazel"));

        registry.contributeDetectors("bazel", "Bazel", List.of("BUILD", "BUILD.bazel", "WORKSPACE", "WORKSPACE.bazel"));

        ProjectProfile bazel = registry.getProfile("bazel");
        assertNotNull(bazel);
        assertTrue(bazel.projectDetectors().contains("BUILD"));
        assertTrue(bazel.projectDetectors().contains("BUILD.bazel"));

        assertTrue(registry.detectProfiles(Path.of("BUILD")).contains("bazel"));
        assertTrue(registry.detectProfiles(Path.of("BUILD.bazel")).contains("bazel"));
        assertTrue(registry.detectProfiles(Path.of("WORKSPACE")).contains("bazel"));
        assertTrue(registry.detectProfiles(Path.of("WORKSPACE.bazel")).contains("bazel"));
    }

    @Test
    void contributeClasspathDetectorToMavenAndGradle() {
        assertFalse(registry.getProfile("maven").projectDetectors().contains(".classpath"));
        assertFalse(registry.getProfile("gradle").projectDetectors().contains(".classpath"));

        registry.contributeDetectors("maven", "Maven", List.of(".classpath"));
        registry.contributeDetectors("gradle", "Gradle", List.of(".classpath"));

        assertTrue(registry.getProfile("maven").projectDetectors().contains(".classpath"));
        assertTrue(registry.getProfile("gradle").projectDetectors().contains(".classpath"));

        Set<String> profiles = registry.detectProfiles(Path.of(".classpath"));
        assertTrue(profiles.contains("maven"));
        assertTrue(profiles.contains("gradle"));
    }

    @Test
    void contributeAdditionalDetectorsToExistingProfile() {
        ProjectProfile mavenBefore = registry.getProfile("maven");
        int detectorsBefore = mavenBefore.projectDetectors().size();

        registry.contributeDetectors("maven", "Maven", List.of("pom.xml.bak"));

        ProjectProfile mavenAfter = registry.getProfile("maven");
        assertEquals(detectorsBefore + 1, mavenAfter.projectDetectors().size());
        assertTrue(mavenAfter.projectDetectors().contains("pom.xml.bak"));

        Set<String> profiles = registry.detectProfiles(Path.of("pom.xml.bak"));
        assertTrue(profiles.contains("maven"));
    }

    @Test
    void contributeDuplicateDetectorsAreDeduped() {
        ProjectProfile mavenBefore = registry.getProfile("maven");
        int detectorsBefore = mavenBefore.projectDetectors().size();

        registry.contributeDetectors("maven", "Maven", List.of("pom.xml"));

        ProjectProfile mavenAfter = registry.getProfile("maven");
        assertEquals(detectorsBefore, mavenAfter.projectDetectors().size());
    }

    @Test
    void contributeNullOrEmptyIsNoOp() {
        int profilesBefore = registry.getAllProfiles().size();

        registry.contributeDetectors(null, null, null);
        registry.contributeDetectors("test", "Test", null);
        registry.contributeDetectors("test", "Test", List.of());

        assertEquals(profilesBefore, registry.getAllProfiles().size());
    }

    @Test
    void contributeGlobDetector() {
        registry.contributeDetectors("maven", "Maven", List.of("*.ant"));

        Set<String> profiles = registry.detectProfiles(Path.of("build.ant"));
        assertTrue(profiles.contains("maven"));
    }

    // =========================================================
    // Registry queries
    // =========================================================

    @Test
    void getAllProfilesReturnsAll() {
        assertTrue(registry.getAllProfiles().size() >= 29);
    }

    @Test
    void getProfileIdsMatchesProfiles() {
        Set<String> ids = registry.getProfileIds();
        assertTrue(ids.contains("maven"));
        assertTrue(ids.contains("gradle"));
        assertTrue(ids.contains("npm"));
        assertTrue(ids.contains("cargo"));
    }

    @Test
    void getProfileReturnsNullForUnknown() {
        assertNull(registry.getProfile("nonexistent"));
    }

    // =========================================================
    // ProjectProfile record validation
    // =========================================================

    @Test
    void profileRequiresNonBlankId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectProfile(null, "Test", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectProfile("", "Test", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectProfile("  ", "Test", List.of()));
    }

    @Test
    void profileDefaultsNullDetectorsToEmptyList() {
        ProjectProfile profile = new ProjectProfile("test", "Test", null);
        assertNotNull(profile.projectDetectors());
        assertTrue(profile.projectDetectors().isEmpty());
    }

    @Test
    void profileStoresFieldsCorrectly() {
        ProjectProfile profile = new ProjectProfile("maven", "Maven", List.of("pom.xml"));
        assertEquals("maven", profile.id());
        assertEquals("Maven", profile.name());
        assertEquals(List.of("pom.xml"), profile.projectDetectors());
    }

    // =========================================================
    // Real-world scenario tests
    // =========================================================

    @Test
    void javaLsOnlyStartsForMaven() {
        Set<String> pomProfiles = registry.detectProfiles(Path.of("pom.xml"));
        Set<String> gradleProfiles = registry.detectProfiles(Path.of("build.gradle"));

        List<String> javaLsProfiles = List.of("maven");

        assertTrue(pomProfiles.stream().anyMatch(javaLsProfiles::contains),
                "java-ls should start for pom.xml (Maven project)");
        assertFalse(gradleProfiles.stream().anyMatch(javaLsProfiles::contains),
                "java-ls should NOT start for build.gradle (Gradle project)");
    }

    @Test
    void jdtlsStartsForBothMavenAndGradle() {
        Set<String> pomProfiles = registry.detectProfiles(Path.of("pom.xml"));
        Set<String> gradleProfiles = registry.detectProfiles(Path.of("build.gradle"));

        List<String> jdtlsProfiles = List.of("maven", "gradle");

        assertTrue(pomProfiles.stream().anyMatch(jdtlsProfiles::contains),
                "JDT.LS should start for pom.xml (Maven project)");
        assertTrue(gradleProfiles.stream().anyMatch(jdtlsProfiles::contains),
                "JDT.LS should start for build.gradle (Gradle project)");
    }

    @Test
    void classPathContributedByJdtlsActivatesBothProfiles() {
        registry.contributeDetectors("maven", "Maven", List.of(".classpath"));
        registry.contributeDetectors("gradle", "Gradle", List.of(".classpath"));

        Set<String> profiles = registry.detectProfiles(Path.of(".classpath"));
        List<String> jdtlsProfiles = List.of("maven", "gradle");

        assertTrue(profiles.stream().anyMatch(jdtlsProfiles::contains),
                ".classpath should activate JDT.LS (supports maven and gradle)");

        List<String> javaLsProfiles = List.of("maven");
        assertTrue(profiles.stream().anyMatch(javaLsProfiles::contains),
                ".classpath should also activate java-ls (maven profile matches)");
    }

    @Test
    void bazelExtensionCreatesNewProfile() {
        registry.contributeDetectors("bazel", "Bazel",
                List.of("BUILD", "BUILD.bazel", "WORKSPACE", "WORKSPACE.bazel"));

        Set<String> profiles = registry.detectProfiles(Path.of("BUILD.bazel"));

        List<String> jdtlsProfiles = List.of("maven", "gradle");
        List<String> javaLsProfiles = List.of("maven");

        assertTrue(profiles.contains("bazel"));
        assertFalse(profiles.stream().anyMatch(jdtlsProfiles::contains),
                "BUILD.bazel should NOT match maven/gradle profiles");
        assertFalse(profiles.stream().anyMatch(javaLsProfiles::contains),
                "BUILD.bazel should NOT match java-ls profiles");
    }

    @Test
    void pythonExtensionMatchesAllPythonBuildSystems() {
        List<String> pythonExtProfiles = List.of("pip", "setuptools", "pyproject");

        assertTrue(registry.detectProfiles(Path.of("requirements.txt")).stream()
                .anyMatch(pythonExtProfiles::contains));
        assertTrue(registry.detectProfiles(Path.of("setup.py")).stream()
                .anyMatch(pythonExtProfiles::contains));
        assertTrue(registry.detectProfiles(Path.of("pyproject.toml")).stream()
                .anyMatch(pythonExtProfiles::contains));
    }

    @Test
    void multipleProfilesDetectedInSameWorkspace() {
        Set<String> allProfiles = new java.util.HashSet<>();
        allProfiles.addAll(registry.detectProfiles(Path.of("pom.xml")));
        allProfiles.addAll(registry.detectProfiles(Path.of("package.json")));

        assertTrue(allProfiles.contains("maven"), "Maven should be detected");
        assertTrue(allProfiles.contains("npm"), "npm should be detected");
        assertFalse(allProfiles.contains("gradle"), "Gradle should not be detected");
    }
}
