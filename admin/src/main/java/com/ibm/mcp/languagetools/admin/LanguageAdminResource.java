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
package com.ibm.mcp.languagetools.admin;

import com.ibm.mcp.languagetools.Application;
import com.ibm.mcp.languagetools.language.DocumentFilter;
import com.ibm.mcp.languagetools.language.DocumentSelector;
import com.ibm.mcp.languagetools.language.LanguageDefinition;
import com.ibm.mcp.languagetools.language.LanguageRegistry;
import com.ibm.mcp.languagetools.server.ServerConfigBase;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.*;

/**
 * REST endpoint for language admin operations.
 * Lists all registered languages (from languages.json and server documentSelectors)
 * with their associated servers.
 */
@Path("/api/admin/languages")
@Produces(MediaType.APPLICATION_JSON)
public class LanguageAdminResource {

    @Inject
    LanguageRegistry languageRegistry;

    @Inject
    Application application;

    @GET
    public List<Map<String, Object>> listLanguages() {
        Map<String, Map<String, Object>> languageMap = new LinkedHashMap<>();

        for (LanguageDefinition lang : languageRegistry.getAllLanguages()) {
            languageMap.put(lang.id(), buildLanguageDTO(lang));
        }

        for (var config : application.getLspServerConfigs()) {
            addServerAssociation(languageMap, config, "lsp");
        }
        for (var config : application.getDapServerConfigs()) {
            addServerAssociation(languageMap, config, "dap");
        }
        for (var config : application.getBspServerConfigs()) {
            addServerAssociation(languageMap, config, "bsp");
        }

        return new ArrayList<>(languageMap.values());
    }

    @SuppressWarnings("unchecked")
    private void addServerAssociation(Map<String, Map<String, Object>> languageMap,
                                      ServerConfigBase config, String serverType) {
        DocumentSelector ds = config.getDocumentSelector();
        if (ds == null || ds.isEmpty()) return;

        for (DocumentFilter filter : ds.getFilters()) {
            String langId = filter.getLanguage();
            if (langId == null) continue;

            Map<String, Object> dto = languageMap.get(langId);
            if (dto == null) {
                dto = new LinkedHashMap<>();
                dto.put("id", langId);
                dto.put("aliases", List.of());
                dto.put("extensions", List.of());
                dto.put("filenames", List.of());
                dto.put("filenamePatterns", List.of());
                dto.put("source", "server");
                dto.put("servers", new LinkedHashMap<String, List<Map<String, Object>>>());
                languageMap.put(langId, dto);
            }

            Map<String, List<Map<String, Object>>> servers =
                    (Map<String, List<Map<String, Object>>>) dto.get("servers");
            Map<String, Object> serverInfo = new LinkedHashMap<>();
            serverInfo.put("id", config.getServerId());
            serverInfo.put("name", config.getName());
            if (filter.getPattern() != null) {
                serverInfo.put("pattern", filter.getPattern());
            }
            if (filter.getScheme() != null) {
                serverInfo.put("scheme", filter.getScheme());
            }
            servers.computeIfAbsent(serverType, k -> new ArrayList<>()).add(serverInfo);
        }
    }

    private Map<String, Object> buildLanguageDTO(LanguageDefinition lang) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", lang.id());
        dto.put("aliases", lang.aliases());
        dto.put("extensions", lang.extensions());
        dto.put("filenames", lang.filenames());
        dto.put("filenamePatterns", lang.filenamePatterns());
        if (lang.firstLine() != null) {
            dto.put("firstLine", lang.firstLine());
        }
        dto.put("source", "global");
        dto.put("servers", new LinkedHashMap<String, List<Map<String, String>>>());
        return dto;
    }
}
