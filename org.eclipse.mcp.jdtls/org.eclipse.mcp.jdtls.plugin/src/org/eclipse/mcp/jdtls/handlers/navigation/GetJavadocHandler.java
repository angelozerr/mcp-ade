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
package org.eclipse.mcp.jdtls.handlers.navigation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Handler for "mcp.jdtls.getJavadoc" command.
 *
 * <p>Arguments: [{uri, line, character}]</p>
 *
 * <p>Resolves the element at the given position and extracts the Javadoc
 * comment. Parses the raw Javadoc into structured sections: description,
 * {@code @param}, {@code @return}, {@code @throws}, {@code @see}, and
 * {@code @since} tags.</p>
 *
 * <p>Copied and adapted from
 * <a href="https://github.com/pzalutski-pixel/javalens-mcp/blob/master/org.javalens.mcp/src/org/javalens/mcp/tools/GetJavadocTool.java">javalens-mcp GetJavadocTool</a>
 * for JDT.LS delegate command handler architecture.</p>
 */
public class GetJavadocHandler extends AbstractPositionHandler {

    private static final Pattern PARAM_PATTERN = Pattern.compile("@param\\s+(\\S+)\\s+(.+)");
    private static final Pattern THROWS_PATTERN = Pattern.compile("@throws\\s+(\\S+)\\s+(.+)");
    private static final Pattern RETURN_PATTERN = Pattern.compile("@return\\s+(.+)");
    private static final Pattern SEE_PATTERN = Pattern.compile("@see\\s+(.+)");
    private static final Pattern SINCE_PATTERN = Pattern.compile("@since\\s+(.+)");

    @Override
    protected Object handleElements(IJavaElement[] elements, ICompilationUnit cu, int offset,
            IProgressMonitor monitor) throws Exception {
        if (elements.length == 0) {
            throw new RuntimeException("No element found at position");
        }

        IJavaElement element = elements[0];
        Map<String, Object> result = new HashMap<>();
        result.put("element", element.getElementName());

        if (!(element instanceof IMember member)) {
            throw new RuntimeException("Element is not a member with Javadoc");
        }

        String rawJavadoc = extractSourceJavadoc(member);
        if (rawJavadoc == null) {
            // Fall back to attached Javadoc
            try {
                rawJavadoc = member.getAttachedJavadoc(monitor);
            } catch (JavaModelException e) {
                // Not available
            }
        }

        if (rawJavadoc == null) {
            result.put("javadoc", Map.of("description", "No Javadoc available"));
            return result;
        }

        result.put("javadoc", parseJavadoc(rawJavadoc));
        return result;
    }

    /**
     * Extract the raw Javadoc comment text from source using
     * {@link IMember#getJavadocRange()}.
     */
    private String extractSourceJavadoc(IMember member) {
        try {
            ISourceRange javadocRange = member.getJavadocRange();
            if (javadocRange == null) {
                return null;
            }

            ICompilationUnit memberCu = member.getCompilationUnit();
            if (memberCu == null) {
                return null;
            }

            String source = memberCu.getSource();
            if (source == null) {
                return null;
            }

            int start = javadocRange.getOffset();
            int end = start + javadocRange.getLength();
            if (end > source.length()) {
                end = source.length();
            }

            return source.substring(start, end);
        } catch (JavaModelException e) {
            return null;
        }
    }

    /**
     * Parse a raw Javadoc comment into structured sections.
     */
    private Map<String, Object> parseJavadoc(String rawJavadoc) {
        Map<String, Object> parsed = new HashMap<>();

        // Strip comment delimiters and leading asterisks
        String cleaned = cleanJavadoc(rawJavadoc);
        String[] lines = cleaned.split("\\n");

        StringBuilder description = new StringBuilder();
        List<Map<String, String>> params = new ArrayList<>();
        String returnTag = null;
        List<Map<String, String>> throwsTags = new ArrayList<>();
        List<String> seeTags = new ArrayList<>();
        String sinceTag = null;

        boolean inDescription = true;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("@")) {
                inDescription = false;

                Matcher paramMatcher = PARAM_PATTERN.matcher(trimmed);
                if (paramMatcher.matches()) {
                    Map<String, String> param = new HashMap<>();
                    param.put("name", paramMatcher.group(1));
                    param.put("description", paramMatcher.group(2).trim());
                    params.add(param);
                    continue;
                }

                Matcher returnMatcher = RETURN_PATTERN.matcher(trimmed);
                if (returnMatcher.matches()) {
                    returnTag = returnMatcher.group(1).trim();
                    continue;
                }

                Matcher throwsMatcher = THROWS_PATTERN.matcher(trimmed);
                if (throwsMatcher.matches()) {
                    Map<String, String> throwsEntry = new HashMap<>();
                    throwsEntry.put("exception", throwsMatcher.group(1));
                    throwsEntry.put("description", throwsMatcher.group(2).trim());
                    throwsTags.add(throwsEntry);
                    continue;
                }

                Matcher seeMatcher = SEE_PATTERN.matcher(trimmed);
                if (seeMatcher.matches()) {
                    seeTags.add(seeMatcher.group(1).trim());
                    continue;
                }

                Matcher sinceMatcher = SINCE_PATTERN.matcher(trimmed);
                if (sinceMatcher.matches()) {
                    sinceTag = sinceMatcher.group(1).trim();
                    continue;
                }
            }

            if (inDescription && !trimmed.isEmpty()) {
                if (description.length() > 0) {
                    description.append('\n');
                }
                description.append(trimmed);
            }
        }

        parsed.put("description", description.toString());
        if (!params.isEmpty()) {
            parsed.put("params", params);
        }
        if (returnTag != null) {
            parsed.put("return", returnTag);
        }
        if (!throwsTags.isEmpty()) {
            parsed.put("throws", throwsTags);
        }
        if (!seeTags.isEmpty()) {
            parsed.put("see", seeTags);
        }
        if (sinceTag != null) {
            parsed.put("since", sinceTag);
        }

        return parsed;
    }

    /**
     * Remove Javadoc comment delimiters and leading asterisks.
     */
    private String cleanJavadoc(String rawJavadoc) {
        // Remove /** and */
        String cleaned = rawJavadoc;
        if (cleaned.startsWith("/**")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("*/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }

        // Remove leading asterisks on each line
        StringBuilder sb = new StringBuilder();
        for (String line : cleaned.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("* ")) {
                trimmed = trimmed.substring(2);
            } else if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1);
            }
            sb.append(trimmed).append('\n');
        }
        return sb.toString();
    }
}
