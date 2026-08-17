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
package com.ibm.mcp.languagetools.extensions.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * SAX-based parsing of Maven POM files.
 *
 * <p>Provides two parsing modes:
 * <ul>
 *   <li>{@link #parseModules(Path)} — lightweight, reads only {@code <modules>}
 *       and stops parsing immediately after (early exit via SAX exception).</li>
 *   <li>{@link #parseFull(Path)} — reads all top-level POM elements: coordinates,
 *       parent, modules, properties, dependencies, and dependency management.</li>
 * </ul>
 */
final class PomParser {

    private PomParser() {
    }

    static List<String> parseModules(Path pomXml) {
        if (!Files.exists(pomXml)) {
            return List.of();
        }
        try {
            List<String> modules = new ArrayList<>();
            createFactory().newSAXParser().parse(pomXml.toFile(), new ModulesHandler(modules));
            return modules;
        } catch (ModulesDoneException e) {
            return e.modules;
        } catch (Exception e) {
            return List.of();
        }
    }

    static PomInfo parseFull(Path pomFile) {
        try {
            var handler = new FullPomHandler();
            createFactory().newSAXParser().parse(pomFile.toFile(), handler);
            return handler.toResult();
        } catch (Exception e) {
            return null;
        }
    }

    private static SAXParserFactory createFactory() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory;
    }

    // ---- Modules-only parser (early exit) ----

    private static final class ModulesDoneException extends SAXException {
        final List<String> modules;

        ModulesDoneException(List<String> modules) {
            super("done");
            this.modules = modules;
        }
    }

    private static final class ModulesHandler extends DefaultHandler {
        private final List<String> modules;
        private int depth = 0;
        private boolean inModules = false;
        private boolean inModule = false;
        private final StringBuilder text = new StringBuilder();

        ModulesHandler(List<String> modules) {
            this.modules = modules;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                                 Attributes attributes) {
            depth++;
            if (depth == 2 && "modules".equals(qName)) {
                inModules = true;
            } else if (inModules && "module".equals(qName)) {
                inModule = true;
                text.setLength(0);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inModule) {
                text.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (inModule && "module".equals(qName)) {
                String name = text.toString().trim();
                if (!name.isEmpty()) {
                    modules.add(name);
                }
                inModule = false;
            } else if (inModules && "modules".equals(qName)) {
                throw new ModulesDoneException(modules);
            }
            depth--;
        }
    }

    // ---- Full POM parser ----

    static class PomInfo {
        String artifactId;
        String groupId;
        String version;
        String packaging;
        boolean hasParent;
        String parentRelativePath;
        final List<String> moduleNames = new ArrayList<>();
        final List<MavenDependency> dependencies = new ArrayList<>();
        final Map<String, String> properties = new HashMap<>();
        final Map<String, String> managedVersions = new HashMap<>();

        boolean isReactorPom() {
            return "pom".equals(packaging) && !moduleNames.isEmpty();
        }
    }

    record MavenDependency(String groupId, String artifactId, String version) {
    }

    private static class FullPomHandler extends DefaultHandler {

        private int depth;
        private StringBuilder text;

        private String artifactId;
        private String groupId;
        private String version;
        private String packaging;

        private boolean inParent;
        private boolean hasParent;
        private String parentRelativePath;

        private boolean inModules;
        private final List<String> moduleNames = new ArrayList<>();

        private boolean inProperties;
        private String currentPropertyName;
        private final Map<String, String> properties = new HashMap<>();

        private boolean inTopLevelDependencies;
        private boolean inDependency;
        private String depGroupId;
        private String depArtifactId;
        private String depVersion;
        private String depScope;
        private final List<MavenDependency> dependencies = new ArrayList<>();

        private boolean inDependencyManagement;
        private boolean inDmDependencies;
        private boolean inDmDependency;
        private String dmDepGroupId;
        private String dmDepArtifactId;
        private String dmDepVersion;
        private final Map<String, String> managedVersions = new HashMap<>();

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            depth++;
            text = new StringBuilder();

            if (depth == 2) {
                switch (qName) {
                    case "parent" -> { inParent = true; hasParent = true; }
                    case "properties" -> inProperties = true;
                    case "modules" -> inModules = true;
                    case "dependencies" -> inTopLevelDependencies = true;
                    case "dependencyManagement" -> inDependencyManagement = true;
                }
            } else if (depth == 3) {
                if (inTopLevelDependencies && "dependency".equals(qName)) {
                    inDependency = true;
                    depGroupId = null;
                    depArtifactId = null;
                    depVersion = null;
                    depScope = null;
                }
                if (inDependencyManagement && "dependencies".equals(qName)) {
                    inDmDependencies = true;
                }
                if (inProperties) {
                    currentPropertyName = qName;
                }
            } else if (depth == 4 && inDmDependencies && "dependency".equals(qName)) {
                inDmDependency = true;
                dmDepGroupId = null;
                dmDepArtifactId = null;
                dmDepVersion = null;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (text != null) {
                text.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            String content = text != null ? text.toString().trim() : null;

            if (depth == 2) {
                switch (qName) {
                    case "artifactId" -> artifactId = content;
                    case "groupId" -> groupId = content;
                    case "version" -> version = content;
                    case "packaging" -> packaging = content;
                    case "parent" -> inParent = false;
                    case "properties" -> inProperties = false;
                    case "modules" -> inModules = false;
                    case "dependencies" -> inTopLevelDependencies = false;
                    case "dependencyManagement" -> inDependencyManagement = false;
                }
            } else if (depth == 3) {
                if (inParent && "relativePath".equals(qName)) {
                    parentRelativePath = content;
                }
                if (inModules && "module".equals(qName) && content != null && !content.isEmpty()) {
                    moduleNames.add(content);
                }
                if (inProperties && currentPropertyName != null) {
                    if (content != null) {
                        properties.put(currentPropertyName, content);
                    }
                    currentPropertyName = null;
                }
                if (inDependency && "dependency".equals(qName)) {
                    if (depGroupId != null && depArtifactId != null) {
                        if (depScope == null || "compile".equals(depScope) || "runtime".equals(depScope)) {
                            dependencies.add(new MavenDependency(depGroupId, depArtifactId, depVersion));
                        }
                    }
                    inDependency = false;
                }
                if (inDependencyManagement && "dependencies".equals(qName)) {
                    inDmDependencies = false;
                }
            } else if (depth == 4) {
                if (inDependency) {
                    switch (qName) {
                        case "groupId" -> depGroupId = content;
                        case "artifactId" -> depArtifactId = content;
                        case "version" -> depVersion = content;
                        case "scope" -> depScope = content;
                    }
                }
                if (inDmDependency && "dependency".equals(qName)) {
                    if (dmDepGroupId != null && dmDepArtifactId != null && dmDepVersion != null) {
                        managedVersions.put(dmDepGroupId + ":" + dmDepArtifactId, dmDepVersion);
                    }
                    inDmDependency = false;
                }
            } else if (depth == 5 && inDmDependency) {
                switch (qName) {
                    case "groupId" -> dmDepGroupId = content;
                    case "artifactId" -> dmDepArtifactId = content;
                    case "version" -> dmDepVersion = content;
                }
            }

            text = null;
            depth--;
        }

        PomInfo toResult() {
            PomInfo info = new PomInfo();
            info.artifactId = artifactId;
            info.groupId = groupId;
            info.version = version;
            info.packaging = packaging;
            info.hasParent = hasParent;
            info.parentRelativePath = parentRelativePath;
            info.moduleNames.addAll(moduleNames);
            info.dependencies.addAll(dependencies);
            info.properties.putAll(properties);
            info.managedVersions.putAll(managedVersions);
            return info;
        }
    }
}
