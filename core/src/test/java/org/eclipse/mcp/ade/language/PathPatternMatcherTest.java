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
package org.eclipse.mcp.ade.language;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class PathPatternMatcherTest {

    @Test
    void testWindowsAbsolutePathWithGlob() {
        String pattern = "C:\\Users\\XXX\\IdeaProjects\\test-rust/**/*.rs";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.rs", matcher.getPattern());
        assertNotNull(matcher.getBasePath());
        assertEquals(Paths.get("C:/Users/XXX/IdeaProjects/test-rust"), matcher.getBasePath());
    }

    @Test
    void testUnixAbsolutePathWithGlob() {
        String pattern = "/home/user/project/**/*.java";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.java", matcher.getPattern());
        assertNotNull(matcher.getBasePath());
        assertEquals(Paths.get("/home/user/project"), matcher.getBasePath());
    }

    @Test
    void testRelativePathWithGlob() {
        String pattern = "src/main/java/**/*.kt";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.kt", matcher.getPattern());
        assertNotNull(matcher.getBasePath());
        assertEquals(Paths.get("src/main/java"), matcher.getBasePath());
    }

    @Test
    void testPatternStartingWithGlob() {
        String pattern = "**/*.txt";
        Path defaultBase = Paths.get("/default/path");
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, defaultBase);

        assertEquals("**/*.txt", matcher.getPattern());
        assertEquals(defaultBase, matcher.getBasePath());
    }

    @Test
    void testPatternStartingWithAsterisk() {
        String pattern = "*.rs";
        Path defaultBase = Paths.get("/default");
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, defaultBase);

        assertEquals("*.rs", matcher.getPattern());
        assertEquals(defaultBase, matcher.getBasePath());
    }

    @Test
    void testSingleLevelWildcard() {
        String pattern = "/usr/local/bin/*.sh";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("*.sh", matcher.getPattern());
        assertEquals(Paths.get("/usr/local/bin"), matcher.getBasePath());
    }

    @Test
    void testQuestionMarkWildcard() {
        String pattern = "C:\\test\\folder\\file?.txt";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("file?.txt", matcher.getPattern());
        assertEquals(Paths.get("C:/test/folder"), matcher.getBasePath());
    }

    @Test
    void testBracketWildcard() {
        String pattern = "/var/log/app[123].log";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("app[123].log", matcher.getPattern());
        assertEquals(Paths.get("/var/log"), matcher.getBasePath());
    }

    @Test
    void testBraceWildcard() {
        String pattern = "/etc/config/{prod,dev,test}.yml";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("{prod,dev,test}.yml", matcher.getPattern());
        assertEquals(Paths.get("/etc/config"), matcher.getBasePath());
    }

    @Test
    void testMultipleGlobTypes() {
        String pattern = "C:\\projects\\myapp\\**/*.{java,kt,rs}";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.{java,kt,rs}", matcher.getPattern());
        assertEquals(Paths.get("C:/projects/myapp"), matcher.getBasePath());
    }

    @Test
    void testMixedSeparators() {
        String pattern = "C:\\Users/XXX/project\\src/**/*.java";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.java", matcher.getPattern());
        assertNotNull(matcher.getBasePath());
    }

    @Test
    void testNoGlobCharacters() {
        String pattern = "/home/user/file.txt";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("/home/user/file.txt", matcher.getPattern());
        assertNull(matcher.getBasePath());
    }

    @Test
    void testGlobAtBeginning() {
        String pattern = "**/src/main/**/*.java";
        Path defaultBase = Paths.get("/project");
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, defaultBase);

        assertEquals("**/src/main/**/*.java", matcher.getPattern());
        assertEquals(defaultBase, matcher.getBasePath());
    }

    @Test
    void testEmptyPattern() {
        String pattern = "";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("", matcher.getPattern());
        assertNull(matcher.getBasePath());
    }

    @Test
    void testOnlySeparatorAndGlob() {
        String pattern = "/*.txt";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("*.txt", matcher.getPattern());
    }

    @Test
    void testDeepNestedPath() {
        String pattern = "/a/b/c/d/e/f/g/h/**/*.xml";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.xml", matcher.getPattern());
        assertEquals(Paths.get("/a/b/c/d/e/f/g/h"), matcher.getBasePath());
    }

    @Test
    void testWindowsDriveLetterOnly() {
        String pattern = "C:/**/*.dll";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.dll", matcher.getPattern());
    }

    @Test
    void testTrailingSlashBeforeGlob() {
        String pattern = "/home/user/project//**/*.js";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.js", matcher.getPattern());
        assertEquals(Paths.get("/home/user/project"), matcher.getBasePath());
    }

    @Test
    void testUNCPath() {
        String pattern = "\\\\server\\share\\folder/**/*.doc";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.doc", matcher.getPattern());
        assertNotNull(matcher.getBasePath());
    }

    @Test
    void testOnlyFilenameGlob() {
        String pattern = "test*.java";
        Path defaultBase = Paths.get("/src");
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, defaultBase);

        assertEquals("test*.java", matcher.getPattern());
        assertEquals(defaultBase, matcher.getBasePath());
    }

    @Test
    void testDefaultBasePathUsed() {
        String pattern = "*.rs";
        Path defaultBase = Paths.get("/my/default/path");
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, defaultBase);

        assertNotNull(matcher.getBasePath());
        assertEquals(defaultBase, matcher.getBasePath());
    }

    @Test
    void testConsecutiveAsterisks() {
        String pattern = "/path/to/***/*.txt";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("***/*.txt", matcher.getPattern());
        assertEquals(Paths.get("/path/to"), matcher.getBasePath());
    }

    @Test
    void testDotInDirectoryName() {
        String pattern = "/home/user/.config/**/*.conf";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.conf", matcher.getPattern());
        assertEquals(Paths.get("/home/user/.config"), matcher.getBasePath());
    }

    @Test
    void testSpacesInPath() {
        String pattern = "/home/my documents/project/**/*.pdf";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.pdf", matcher.getPattern());
        assertEquals(Paths.get("/home/my documents/project"), matcher.getBasePath());
    }

    @Test
    void testUnicodeInPath() {
        String pattern = "/home/utilisateur/projét/**/*.txt";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.txt", matcher.getPattern());
        assertEquals(Paths.get("/home/utilisateur/projét"), matcher.getBasePath());
    }

    @Test
    void testRealWorldRustProject() {
        String pattern = "C:\\Users\\Developer\\RustProjects\\my-app\\src/**/*.rs";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.rs", matcher.getPattern());
        assertEquals(Paths.get("C:/Users/Developer/RustProjects/my-app/src"), matcher.getBasePath());
    }

    @Test
    void testRealWorldJavaMavenProject() {
        String pattern = "/home/dev/projects/my-service/src/main/java/**/*.java";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.java", matcher.getPattern());
        assertEquals(Paths.get("/home/dev/projects/my-service/src/main/java"), matcher.getBasePath());
    }

    @Test
    void testRealWorldNodeJsProject() {
        String pattern = "D:\\workspace\\my-node-app\\src\\**\\*.{ts,js}";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**\\*.{ts,js}", matcher.getPattern());
        assertEquals(Paths.get("D:/workspace/my-node-app/src"), matcher.getBasePath());
    }

    @Test
    void testMultipleConsecutiveSeparators() {
        String pattern = "/home//user///project/**/*.txt";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.txt", matcher.getPattern());
    }

    @Test
    void testNullDefaultBasePath() {
        String pattern = "**/*.java";
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern(pattern, null);

        assertEquals("**/*.java", matcher.getPattern());
        assertNull(matcher.getBasePath());
    }

    // --- BasePath relativization in matching ---

    @Test
    void matchesWithBasePath_fileUnderBasePath() {
        PathPatternMatcher matcher = new PathPatternMatcher("**/*.java", Paths.get("/project/src"));
        assertTrue(matcher.matches(Paths.get("/project/src/main/Foo.java")));
    }

    @Test
    void matchesWithBasePath_fileNotUnderBasePath() {
        PathPatternMatcher matcher = new PathPatternMatcher("**/*.java", Paths.get("/project/src"));
        assertFalse(matcher.matches(Paths.get("/other/src/main/Foo.java")));
    }

    @Test
    void matchesWithBasePath_fileAtBasePathRoot() {
        PathPatternMatcher matcher = new PathPatternMatcher("*.java", Paths.get("/project"));
        assertTrue(matcher.matches(Paths.get("/project/Foo.java")));
    }

    @Test
    void matchesWithBasePath_fileAtBasePathRootDoesNotCrossDir() {
        PathPatternMatcher matcher = new PathPatternMatcher("*.java", Paths.get("/project"));
        assertFalse(matcher.matches(Paths.get("/project/sub/Foo.java")));
    }

    @Test
    void matchesWithBasePath_doubleStarMatchesDeep() {
        PathPatternMatcher matcher = new PathPatternMatcher("**/*.rs", Paths.get("/workspace"));
        assertTrue(matcher.matches(Paths.get("/workspace/src/lib/mod.rs")));
    }

    @Test
    void matchesWithBasePath_exactFilename() {
        PathPatternMatcher matcher = new PathPatternMatcher("bootstrap.properties", Paths.get("/project/src/main/resources"));
        assertTrue(matcher.matches(Paths.get("/project/src/main/resources/bootstrap.properties")));
    }

    @Test
    void matchesWithBasePath_exactFilenameWrongDir() {
        PathPatternMatcher matcher = new PathPatternMatcher("bootstrap.properties", Paths.get("/project/src/main/resources"));
        assertFalse(matcher.matches(Paths.get("/other/bootstrap.properties")));
    }

    @Test
    void matchesWithBasePath_uri() {
        PathPatternMatcher matcher = new PathPatternMatcher("**/*.py", Paths.get("/workspace"));
        assertTrue(matcher.matches(java.net.URI.create("file:///workspace/scripts/run.py")));
    }

    @Test
    void matchesWithBasePath_uriOutsideBasePath() {
        PathPatternMatcher matcher = new PathPatternMatcher("**/*.py", Paths.get("/workspace"));
        assertFalse(matcher.matches(java.net.URI.create("file:///other/scripts/run.py")));
    }

    @Test
    void matchesWithoutBasePath_matchesAbsolutePath() {
        PathPatternMatcher matcher = new PathPatternMatcher("**/*.java", null);
        assertTrue(matcher.matches(Paths.get("/any/path/Foo.java")));
    }

    @Test
    void matchesWithBasePath_fromPattern_relative() {
        PathPatternMatcher matcher = PathPatternMatcher.fromPattern("src/**/*.kt", Paths.get("/project"));
        assertEquals("**/*.kt", matcher.getPattern());
        assertEquals(Paths.get("/project/src"), matcher.getBasePath());
        assertTrue(matcher.matches(Paths.get("/project/src/main/App.kt")));
        assertFalse(matcher.matches(Paths.get("/other/src/main/App.kt")));
    }
}
