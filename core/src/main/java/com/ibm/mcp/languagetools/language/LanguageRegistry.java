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
package com.ibm.mcp.languagetools.language;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Registry of language definitions loaded from languages.json.
 * Matches files to language IDs using VSCode-style matching rules.
 * <p>
 * Matching is optimized with pre-indexed maps:
 * <ol>
 *   <li>Exact filename match (Map lookup)</li>
 *   <li>Extension match (Map lookup)</li>
 *   <li>Filename pattern match (glob, iterative)</li>
 *   <li>First line match (regex, iterative)</li>
 * </ol>
 */
@ApplicationScoped
public class LanguageRegistry {

    private static final Logger LOG = Logger.getLogger(LanguageRegistry.class);
    private static final Gson GSON = new Gson();
    private final List<LanguageDefinition> languages;

    private final Map<String, LanguageDefinition> byFilename;
    private final Map<String, LanguageDefinition> byExtension;
    private final List<Map.Entry<PathPatternMatcher, LanguageDefinition>> byPattern;

    public LanguageRegistry() {
        this.languages = loadLanguages();
        this.byFilename = buildFilenameIndex(languages);
        this.byExtension = buildExtensionIndex(languages);
        this.byPattern = buildPatternIndex(languages);
    }

    private static Map<String, LanguageDefinition> buildFilenameIndex(List<LanguageDefinition> languages) {
        Map<String, LanguageDefinition> index = new HashMap<>();
        for (LanguageDefinition lang : languages) {
            for (String filename : lang.filenames()) {
                index.putIfAbsent(filename, lang);
            }
        }
        return index;
    }

    private static Map<String, LanguageDefinition> buildExtensionIndex(List<LanguageDefinition> languages) {
        Map<String, LanguageDefinition> index = new HashMap<>();
        for (LanguageDefinition lang : languages) {
            for (String ext : lang.extensions()) {
                index.putIfAbsent(ext, lang);
            }
        }
        return index;
    }

    private static List<Map.Entry<PathPatternMatcher, LanguageDefinition>> buildPatternIndex(List<LanguageDefinition> languages) {
        List<Map.Entry<PathPatternMatcher, LanguageDefinition>> index = new ArrayList<>();
        for (LanguageDefinition lang : languages) {
            for (String pattern : lang.filenamePatterns()) {
                index.add(Map.entry(new PathPatternMatcher(pattern, null), lang));
            }
        }
        return index;
    }

    private List<LanguageDefinition> loadLanguages() {
        try (InputStream is = getClass().getResourceAsStream("/languages.json")) {
            if (is == null) {
                LOG.warn("languages.json not found, using empty language list");
                return List.of();
            }

            List<LanguageDefinition> loaded = GSON.fromJson(
                new InputStreamReader(is),
                new TypeToken<List<LanguageDefinition>>() {}.getType()
            );

            LOG.infof("Loaded %d language definitions", loaded.size());
            return loaded;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to load languages.json");
            return List.of();
        }
    }

    /**
     * Detect language ID from file URI.
     * <p>
     * Matching order follows VS Code's language detection priority:
     * <ol>
     *   <li>Exact filename match (Map lookup)</li>
     *   <li>Filename pattern match (glob)</li>
     *   <li>Extension match (Map lookup)</li>
     *   <li>First line match (regex, requires file content)</li>
     * </ol>
     *
     * @see <a href="https://code.visualstudio.com/api/references/contribution-points#contributes.languages">VS Code contributes.languages</a>
     */
    public Optional<String> detectLanguage(URI fileUri) {
        return detectLanguage(fileUri, null);
    }

    /**
     * Detect language ID from file URI with optional first line content.
     */
    public Optional<String> detectLanguage(URI fileUri, String firstLine) {
        Path filePath = Paths.get(fileUri);
        String filename = filePath.getFileName().toString();

        // 1. Exact filename match (Map lookup)
        LanguageDefinition byName = byFilename.get(filename);
        if (byName != null) {
            return Optional.of(byName.id());
        }

        // 2. Filename pattern match (pre-built PathPatternMatcher)
        for (var entry : byPattern) {
            if (entry.getKey().matches(filePath)) {
                return Optional.of(entry.getValue().id());
            }
        }

        // 3. Extension match (Map lookup)
        int dotIdx = filename.lastIndexOf('.');
        if (dotIdx >= 0) {
            String ext = filename.substring(dotIdx);
            LanguageDefinition byExt = byExtension.get(ext);
            if (byExt != null) {
                return Optional.of(byExt.id());
            }
        }

        // 4. First line match (if content provided)
        if (firstLine != null) {
            for (LanguageDefinition lang : languages) {
                if (lang.firstLine() != null && !lang.firstLine().isEmpty()) {
                    try {
                        if (Pattern.matches(lang.firstLine(), firstLine)) {
                            return Optional.of(lang.id());
                        }
                    } catch (Exception e) {
                        LOG.debugf("Invalid firstLine regex for %s: %s", lang.id(), e.getMessage());
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Create an LSP document from a file URI string.
     * Detects the language ID automatically.
     * The languageId can be null if the language cannot be detected.
     *
     * @param fileUri the file URI as a string
     * @return the LSP document
     */
    public LanguageDocument createDocument(String fileUri) {
        return createDocument(URI.create(fileUri));
    }

    /**
     * Create an LSP document from a file URI.
     * Detects the language ID automatically.
     * The languageId can be null if the language cannot be detected.
     *
     * @param uri the file URI
     * @return the LSP document
     */
    public LanguageDocument createDocument(URI uri) {
        String languageId = detectLanguage(uri).orElse(null);
        return new LanguageDocument(uri, languageId);
    }

    public List<LanguageDefinition> getAllLanguages() {
        return List.copyOf(languages);
    }
}
