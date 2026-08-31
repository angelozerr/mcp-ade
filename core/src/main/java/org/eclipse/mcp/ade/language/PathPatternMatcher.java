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

import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Path pattern matcher.
 */
public class PathPatternMatcher {

    private final String pattern;
    private final Path basePath;
    private List<PathMatcher> pathMatchers;

    public PathPatternMatcher(String pattern, Path basePath) {
        this.pattern = pattern;
        this.basePath = basePath;
    }

    /**
     * Creates a PathPatternMatcher by automatically extracting the base path from the pattern.
     * <p>
     * The base path is the portion of the pattern before any glob characters (*, ?, [, {).
     * The pattern is then adjusted to be relative to this base path.
     *
     * @param fullPattern the glob pattern with optional absolute base path
     * @param defaultBasePath the default base path to use when the pattern is relative
     * @return a new PathPatternMatcher with extracted base path and relative pattern
     */
    public static PathPatternMatcher fromPattern(final String fullPattern,
                                                 Path defaultBasePath) {
        int firstGlobChar = findFirstGlobCharacter(fullPattern);

        if (firstGlobChar == -1) {
            Path patternPath = Paths.get(fullPattern.replace('\\', '/'));
            if (!patternPath.isAbsolute()) {
                return new PathPatternMatcher(fullPattern, defaultBasePath);
            }
            return new PathPatternMatcher(fullPattern, null);
        }

        if (firstGlobChar == 0) {
            return new PathPatternMatcher(fullPattern, defaultBasePath);
        }

        String beforeGlob = fullPattern.substring(0, firstGlobChar);

        int lastSeparator = Math.max(
                beforeGlob.lastIndexOf('/'),
                beforeGlob.lastIndexOf('\\')
        );

        if (lastSeparator == -1) {
            return new PathPatternMatcher(fullPattern, defaultBasePath);
        }

        String basePathStr = fullPattern.substring(0, lastSeparator);
        String relativePattern = fullPattern.substring(lastSeparator + 1);

        try {
            Path extractedPath = Paths.get(basePathStr.replace('\\', '/'));
            Path basePath;
            if (extractedPath.isAbsolute()) {
                basePath = extractedPath;
            } else if (defaultBasePath != null) {
                basePath = defaultBasePath.resolve(extractedPath);
            } else {
                basePath = extractedPath;
            }
            return new PathPatternMatcher(relativePattern, basePath);
        } catch (Exception e) {
            return new PathPatternMatcher(fullPattern, defaultBasePath);
        }
    }

    private static int findFirstGlobCharacter(String pattern) {
        int minIndex = Integer.MAX_VALUE;

        int asterisk = pattern.indexOf('*');
        if (asterisk != -1 && asterisk < minIndex) {
            minIndex = asterisk;
        }

        int question = pattern.indexOf('?');
        if (question != -1 && question < minIndex) {
            minIndex = question;
        }

        int bracket = pattern.indexOf('[');
        if (bracket != -1 && bracket < minIndex) {
            minIndex = bracket;
        }

        int brace = pattern.indexOf('{');
        if (brace != -1 && brace < minIndex) {
            minIndex = brace;
        }

        return minIndex == Integer.MAX_VALUE ? -1 : minIndex;
    }

    /**
     * Expand the given pattern. ex: ** /foo -> foo, ** /foo.
     *
     * @param pattern the pattern
     * @return the given pattern.
     */
    public static List<String> expandPatterns(String pattern) {
        Parts parts = getParts(pattern);
        if (parts != null) {
            List<String> expanded = new ArrayList<>();
            List<int[]> combinations = generateCombinations(parts.cols().size());
            for (int[] combination : combinations) {
                List<String> expand = new ArrayList<>(parts.parts());
                for (int i = 0; i < combination.length; i++) {
                    if (combination[i] == 0) {
                        int col = parts.cols().get(i);
                        expand.set(col, "");
                    }
                }
                expanded.add(String.join("", expand));
            }
            return expanded;
        }
        return Collections.singletonList(pattern);
    }

    private static Parts getParts(String pattern) {
        int from = 0;
        int index = getNextIndex(pattern, from);
        if (index != -1) {
            List<Integer> cols = new ArrayList<>();
            List<String> parts = new ArrayList<>();
            while (index != -1) {
                String s = pattern.substring(from, index);
                if (!s.isEmpty()) {
                    parts.add(s);
                }
                cols.add(parts.size());
                from = index + 3;
                parts.add(pattern.substring(index, from));
                index += 3;
                index = getNextIndex(pattern, index);
            }
            parts.add(pattern.substring(from));
            return new Parts(parts, cols);
        }
        return null;
    }

    private static int getNextIndex(String pattern, int fromIndex) {
        int startSlashIndex = pattern.indexOf("**/", fromIndex);
        int endSlashIndex = pattern.indexOf("/**", fromIndex);
        if (startSlashIndex != -1 || endSlashIndex != -1) {
            if (startSlashIndex == -1) {
                return endSlashIndex;
            }
            if (endSlashIndex == -1) {
                return startSlashIndex;
            }
            return Math.min(startSlashIndex, endSlashIndex);
        }
        return -1;
    }

    public static List<int[]> generateCombinations(int N) {
        List<int[]> combinations = new ArrayList<>();
        generateCombinationsHelper(N, new int[N], 0, combinations);
        return combinations;
    }

    private static void generateCombinationsHelper(int N, int[] combination, int index, List<int[]> combinations) {
        if (index == N) {
            combinations.add(combination.clone());
        } else {
            combination[index] = 0;
            generateCombinationsHelper(N, combination, index + 1, combinations);
            combination[index] = 1;
            generateCombinationsHelper(N, combination, index + 1, combinations);
        }
    }

    public String getPattern() {
        return pattern;
    }

    public Path getBasePath() {
        return basePath;
    }

    public boolean matches(URI uri) {
        return internalMatches(uri, null);
    }

    public boolean matches(Path path) {
        return internalMatches(null, path);
    }

    private boolean internalMatches(URI uri, Path path) {
        if (pattern.isEmpty()) {
            return false;
        }
        if (pathMatchers == null) {
            createPathMatchers();
        }
        try {
            path = path == null ? Paths.get(uri) : path;
            if (basePath != null) {
                if (!path.startsWith(basePath)) {
                    return false;
                }
                path = basePath.relativize(path);
            }
            for (PathMatcher pathMatcher : pathMatchers) {
                try {
                    if (pathMatcher.matches(path)) {
                        return true;
                    }
                } catch (Exception e) {
                    // Do nothing
                }
            }
        } catch (Exception e) {
            // Do nothing
        }
        return false;
    }

    private synchronized void createPathMatchers() {
        if (pathMatchers != null) {
            return;
        }
        String glob = pattern.replace("\\", "/");
        List<String> expandedPatterns = expandPatterns(glob);
        List<PathMatcher> pathMatchers = new ArrayList<>();
        for (var expandedPattern : expandedPatterns) {
            try {
                PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" + expandedPattern);
                pathMatchers.add(pathMatcher);
            } catch (Exception e) {
                // Do nothing
            }
        }
        this.pathMatchers = pathMatchers;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof PathPatternMatcher other) {
            if (!Objects.deepEquals(pathMatchers, other.pathMatchers)) {
                return false;
            }
            return Objects.equals(pattern, other.getPattern());
        }
        return false;
    }

    record Parts(List<String> parts, List<Integer> cols) {
    }
}
